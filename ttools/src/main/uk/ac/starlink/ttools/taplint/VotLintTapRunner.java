package uk.ac.starlink.ttools.taplint;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.w3c.dom.Node;
import uk.ac.starlink.table.DefaultValueInfo;
import uk.ac.starlink.table.DescribedValue;
import uk.ac.starlink.table.StarTable;
import uk.ac.starlink.table.StoragePolicy;
import uk.ac.starlink.table.ValueInfo;
import uk.ac.starlink.ttools.votlint.VotableVersion;
import uk.ac.starlink.ttools.votlint.VotLintContentHandler;
import uk.ac.starlink.ttools.votlint.VotLintContext;
import uk.ac.starlink.ttools.votlint.VotLintEntityResolver;
import uk.ac.starlink.util.DOMUtils;
import uk.ac.starlink.util.StarEntityResolver;
import uk.ac.starlink.vo.TapQuery;
import uk.ac.starlink.vo.UwsJob;
import uk.ac.starlink.votable.TableElement;
import uk.ac.starlink.votable.VODocument;
import uk.ac.starlink.votable.VOElement;
import uk.ac.starlink.votable.VOStarTable;
import uk.ac.starlink.votable.VOTableDOMBuilder;

/**
 * TapRunner implementation which uses the VotLint validation classes
 * to check the query's result VOTable.
 *
 * @author   Mark Taylor
 * @since    10 Jun 2011
 */
public abstract class VotLintTapRunner extends TapRunner {

    /** Result table parameter set if table was marked overflowed. */
    public static ValueInfo OVERFLOW_INFO =
        new DefaultValueInfo( "OVERFLOW", Boolean.class,
                              "Is TAP result overflow status set?" );

    /**
     * Constructor.
     */
    protected VotLintTapRunner( String name ) {
        super( name );
    }

    /**
     * Execute a TAP query and return a URL connection giving its result.
     *
     * @param  reporter  validation message destination
     * @param  tq  query
     * @return   result data source
     */
    protected abstract URLConnection getResultConnection( Reporter reporter,
                                                          TapQuery tq )
            throws IOException;

    @Override
    protected StarTable executeQuery( Reporter reporter, TapQuery tq )
            throws IOException, SAXException {
        URLConnection conn = getResultConnection( reporter, tq );
        conn = TapQuery.followRedirects( conn );
        String ctype = conn.getContentType();
        if ( ! ( ctype.startsWith( "text/xml" ) ||
                 ctype.startsWith( "application/xml" ) ||
                 ctype.startsWith( "application/x-votable+xml" ) ||
                 ctype.startsWith( "text/x-votable+xml" ) ) ) {
            String msg = new StringBuilder()
               .append( "Bad content type " )
               .append( ctype )
               .append( " for HTTP response which should contain " )
               .append( "VOTable result or error document" )
               .append( " (" )
               .append( conn.getURL() )
               .append( ")" )
               .toString();
            reporter.report( ReportType.ERROR, "VMIM", msg );
        }

        InputStream in = null;
        try {
            in = conn.getInputStream();
        }
        catch ( IOException e ) {
            if ( conn instanceof HttpURLConnection ) {
                in = ((HttpURLConnection) conn).getErrorStream();
            }
            if ( in == null ) {
                throw e;
            }
        }
        return readResultVOTable( reporter, in );
    }

    /**
     * Indicates if the given table, which must have been retrieved from
     * this object's {@link #readResultVOTable} method, was marked as
     * an overflow result.
     *
     * @param   table  TAP result table read by this object
     * @return  true iff overflow
     */
    public boolean isOverflow( StarTable table ) {
        DescribedValue ovParam =
            table.getParameterByName( OVERFLOW_INFO.getName() );
        if ( ovParam != null && ovParam.getValue() instanceof Boolean ) {
            return ((Boolean) ovParam.getValue()).booleanValue();
        }
        else {
            throw new IllegalArgumentException( "Not produced by me!" );
        }
    }

    /**
     * Reads a TAP result VOTable from an input stream, checking it and
     * reporting messages as appropriate.
     * The resulting table will have the {@link #OVERFLOW_INFO} parameter
     * present and set/unset appropriately.
     *
     * @param  reporter  validation message destination
     * @param  in  VOTable input stream
     */
    protected StarTable readResultVOTable( Reporter reporter, InputStream in )
            throws IOException, SAXException {

        /* Set up SAX event handlers to report messages via reporter. */
        ReporterErrorHandler errHandler =
            new ReporterErrorHandler( reporter );
        ReporterVotLintContext vlContext =
            new ReporterVotLintContext( reporter );
        vlContext.setVersion( VotableVersion.V12 );
        vlContext.setValidating( true );
        vlContext.setDebug( false );
        vlContext.setOutput( null );

        /* Set up a SAX event handler to create a DOM. */
        VOTableDOMBuilder domHandler =
            new VOTableDOMBuilder( StoragePolicy.getDefaultPolicy(), true );

        /* Create a SAX parser, install the event handlers and parse the
         * input to get a VODocument. */
        XMLReader parser = createParser( reporter, vlContext );
        parser.setContentHandler( new TeeContentHandler(
                                      new VotLintContentHandler( vlContext ),
                                      domHandler ) );
        parser.setErrorHandler( errHandler );
        parser.parse( new InputSource( in ) );
        VODocument doc = domHandler.getDocument();

        /* Check the top-level element. */
        VOElement voEl = (VOElement) doc.getDocumentElement();
        if ( ! "VOTABLE".equals( voEl.getVOTagName() ) ) {
            String msg = new StringBuffer()
               .append( "Top-level element of result document is " )
               .append( voEl.getTagName() )
               .append( " not VOTABLE" )
               .toString();
            throw new IOException( msg );
        }

        /* Attempt to find the results element. */
        VOElement[] resourceEls = voEl.getChildrenByName( "RESOURCE" );
        VOElement resultsEl = null;
        for ( int ie = 0; ie < resourceEls.length; ie++ ) {
            VOElement el = resourceEls[ ie ];
            if ( "results".equals( el.getAttribute( "type" ) ) ) {
                resultsEl = el;
            }
        }
        if ( resultsEl == null ) {
            if ( resourceEls.length == 1 ) {
                resultsEl = resourceEls[ 0 ];
                reporter.report( ReportType.ERROR, "RRES",
                                 "TAP response document RESOURCE element "
                               + "is not marked type='results'" );
            }
            else {
                throw new IOException( "No RESOURCE with type='results'" );
            }
        }

        /* Look for preceding and possible trailing QUERY_STATUS INFOs
         * and the TABLE. */
        VOElement preStatusInfo = null;
        VOElement postStatusInfo = null;
        TableElement tableEl = null;
        for ( Node node = resultsEl.getFirstChild(); node != null;
              node = node.getNextSibling() ) {
            if ( node instanceof VOElement ) {
                VOElement el = (VOElement) node;
                String name = el.getVOTagName();
                boolean isStatusInfo =
                    "INFO".equals( name ) &&
                    "QUERY_STATUS".equals( el.getAttribute( "name" ) );
                boolean isTable =
                    "TABLE".equals( name );
                if ( isStatusInfo ) {
                    if ( tableEl == null ) {
                        if ( preStatusInfo == null ) {
                            preStatusInfo = el;
                        }
                        else {
                            reporter.report( ReportType.ERROR, "QST1",
                                             "Multiple pre-table INFOs with "
                                           + "name='QUERY_STATUS'" );
                        }
                    }
                    else {
                        if ( postStatusInfo == null ) {
                            postStatusInfo = el;
                        }
                        else {
                            reporter.report( ReportType.ERROR, "QST2",
                                             "Multiple post-table INFOs with "
                                           + "name='QUERY_STATUS'" );
                        }
                    }
                }
                if ( isTable ) {
                    if ( tableEl == null ) {
                        tableEl = (TableElement) el;
                    }
                    else {
                        reporter.report( ReportType.ERROR, "TTOO",
                                         "Multiple TABLEs in results "
                                       + "RESOURCE" );
                    }
                }
            }
        }

        /* Check pre-table status INFO. */
        if ( preStatusInfo == null ) {
            reporter.report( ReportType.ERROR, "NOST",
                             "Missing <INFO name='QUERY_STATUS'> element "
                           + "before TABLE" );
        }
        else {
            String preStatus = preStatusInfo.getAttribute( "value" );
            if ( "ERROR".equals( preStatus ) ) {
                String err = DOMUtils.getTextContent( preStatusInfo );
                if ( err == null || err.trim().length() == 0 ) {
                    reporter.report( ReportType.WARNING, "NOER",
                                     "<INFO name='QUERY_STATUS' value='ERROR'> "
                                   + "element has no message content" );
                    err = "Unknown TAP result error";
                }
                throw new IOException( err );
            }
            else if ( "OK".equals( preStatus ) ) {
                // ok
            }
            else {
                String msg = new StringBuffer()
                    .append( "Pre-table QUERY_STATUS INFO has unknown value " )
                    .append( preStatus )
                    .append( " is not OK/ERROR" )
                    .toString();
                reporter.report( ReportType.ERROR, "QST1", msg );
            }
        }

        /* Check post-table status INFO. */
        boolean overflow = false;
        if ( postStatusInfo != null ) {
            String postStatus = postStatusInfo.getAttribute( "value" );
            if ( "ERROR".equals( postStatus ) ) {
                String err = DOMUtils.getTextContent( postStatusInfo );
                if ( err == null || err.trim().length() == 0 ) {
                    reporter.report( ReportType.WARNING, "NOER",
                                     "<INFO name='QUERY_STATUS' value='ERROR'> "
                                   + "element has no message content" );
                    err = "Unknown TAP result error";
                }
                throw new IOException( err );
            }
            else if ( "OVERFLOW".equals( postStatus ) ) {
                overflow = true;
            }
            else {
                String msg = new StringBuffer()
                    .append( "Post-table QUERY_STATUS INFO has unknown value " )
                    .append( postStatus )
                    .append( " is not ERROR/OVERFLOW" )
                    .toString();
                reporter.report( ReportType.ERROR, "QST2", msg );
            }
        }

        /* Return table if present. */
        if ( tableEl == null ) {
            throw new IOException( "No TABLE element in results RESOURCE" );
        }
        StarTable table = new VOStarTable( tableEl );
        table.setParameter( new DescribedValue( OVERFLOW_INFO,
                                                Boolean.valueOf( overflow ) ) );
        return table;
    }

    /**
     * Returns a SAX parser suitable for use with a VOTable.
     * Its handlers are not set.
     *
     * @param  reporter   validation message destination
     * @param  vlContext  information about votlint config
     */
    private XMLReader createParser( Reporter reporter,
                                    VotLintContext vlContext )
            throws SAXException {

        /* Get a validating or non-validating parser. */
        XMLReader parser;
        try {
            SAXParserFactory spfact = SAXParserFactory.newInstance();
            spfact.setValidating( false );
            spfact.setNamespaceAware( true );
            parser = spfact.newSAXParser().getXMLReader();
        }
        catch ( ParserConfigurationException e ) {
            reporter.report( ReportType.FAILURE, "PRSR",
                             "Trouble setting up XML parse", e );
            throw (SAXException) new SAXException( e.getMessage() )
                                .initCause( e );
        }

        /* Install a custom entity resolver.  This is also installed as
         * a lexical handler, to guarantee that whatever is named in the
         * DOCTYPE declaration is actually interpreted as the VOTable DTD. */
        VotLintEntityResolver entityResolver =
            new VotLintEntityResolver( vlContext );
        try {
            parser.setProperty( "http://xml.org/sax/properties/lexical-handler",
                                entityResolver );
            parser.setEntityResolver( entityResolver );
        }
        catch ( SAXException e ) {
            parser.setEntityResolver( StarEntityResolver.getInstance() );
            reporter.report( ReportType.FAILURE, "XENT",
                             "Entity trouble - DTD validation may not be " +
                              "done properly", e );
        }

        /* Return. */
        return parser;
    }

    /**
     * Returns a new instance which uses HTTP POST to make synchronous queries.
     *
     * @return  new TapRunner
     */
    public static VotLintTapRunner createPostSyncRunner() {
        return new VotLintTapRunner( "sync POST" ) {
            @Override
            protected URLConnection getResultConnection( Reporter reporter,
                                                         TapQuery tq )
                    throws IOException {
                return UwsJob.postForm( new URL( tq.getServiceUrl() + "/sync" ),
                                        tq.getStringParams(),
                                        tq.getStreamParams() );
            }
        };
    }

    /**
     * Returns a new instance which uses HTTP GET to make synchronous queries.
     *
     * @return  new TapRunner
     */
    public static VotLintTapRunner createGetSyncRunner() {
        return new VotLintTapRunner( "sync GET" ) {
            @Override
            protected URLConnection getResultConnection( Reporter reporter,
                                                         TapQuery tq )
                    throws IOException {
                if ( tq.getStreamParams() == null ||
                     tq.getStreamParams().isEmpty() ) {
                    String ptxt =
                        new String( UwsJob
                                   .toPostedBytes( tq.getStringParams() ),
                                    "utf-8" );
                    URL qurl = new URL( tq.getServiceUrl() + "/sync?" + ptxt );
                    reporter.report( ReportType.INFO, "QGET",
                                     "Query GET URL: " + qurl );
                    return qurl.openConnection();
                }
                else {
                    return UwsJob
                          .postForm( new URL( tq.getServiceUrl() + "/sync" ),
                                     tq.getStringParams(),
                                     tq.getStreamParams() );
                }
            }
        };
    }

    /**
     * Returns a new instance which makes asynchronous queries.
     * This instance does not do exhaustive validation.
     *
     * @param  pollMillis  polling interval in milliseconds
     * @return  new TapRunner
     */
    public static VotLintTapRunner createAsyncRunner( final long pollMillis ) {
        return new VotLintTapRunner( "async" ) {
            @Override
            protected URLConnection getResultConnection( Reporter reporter,
                                                         TapQuery tq )
                    throws IOException {
                UwsJob uwsJob =
                    UwsJob.createJob( tq.getServiceUrl()+ "/async",
                                      tq.getStringParams(),
                                      tq.getStreamParams() );
                URL jobUrl = uwsJob.getJobUrl();
                reporter.report( ReportType.INFO, "QJOB",
                                 "Submitted query at " + jobUrl );
                uwsJob.start();
                String phase;
                try {
                    phase = uwsJob.waitForFinish( pollMillis );
                }
                catch ( InterruptedException e ) {
                    throw (IOException)
                          new InterruptedIOException( "interrupted" )
                         .initCause( e );
                }
                if ( "COMPLETED".equals( phase ) ) {
                    uwsJob.setDeleteOnExit( true );
                    return new URL( jobUrl + "/results/result" )
                          .openConnection();
                }
                else if ( "ERROR".equals( phase ) ) {
                    return new URL( jobUrl + "/error" ).openConnection();
                }
                else {
                    throw new IOException( "Unexpected UWS phase " + phase );
                }
            }
        };
    }
}