package uk.ac.starlink.table.formats;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import uk.ac.starlink.table.ColumnInfo;
import uk.ac.starlink.table.RowSequence;
import uk.ac.starlink.table.StarTable;
import uk.ac.starlink.table.StarTableWriter;
import uk.ac.starlink.table.Tables;

/**
 * A StarTableWriter that outputs text to HTML.
 * The output is a single &lt;TABLE&gt; element, that is it has no
 * HTML header.  The output HTML should conform to HTML 3.2.
 *
 * @author   Mark Taylor (Starlink)
 * @see      <http://www.w3.org/TR/REC-html32#table>
 */
public class HTMLTableWriter implements StarTableWriter {

    public String getFormatName() {
        return "HTML";
    }

    public boolean looksLikeFile( String location ) {
        return location.endsWith( ".html" ) ||
               location.endsWith( ".htm" );
    }

    public void writeStarTable( StarTable table, String location )
            throws IOException {

        /* Get a stream for output. */
        OutputStream ostrm = new BufferedOutputStream( getStream( location ) );

        /* Output table header. */
        StringBuffer sbuf = new StringBuffer();
        printLine( ostrm, "<TABLE BORDER='1'>" );
        String tname = table.getName();
        if ( tname != null ) {
            printLine( ostrm,
                       "<CAPTION><STRONG>" + tname + "</STRONG></CAPTION>" );
        }

        /* Output column headings. */
        sbuf = new StringBuffer();
        int ncol = table.getColumnCount();
        ColumnInfo[] colinfos = Tables.getColumnInfos( table );
        String[] names = new String[ ncol ];
        String[] units = new String[ ncol ];
        boolean hasUnits = false;
        for ( int icol = 0; icol < ncol; icol++ ) {
            ColumnInfo colinfo = colinfos[ icol ];
            String name = colinfo.getName();
            String unit = colinfo.getUnitString();
            if ( unit != null ) {
                hasUnits = true;
                unit = "(" + unit + ")";
            }
            names[ icol ] = name;
            units[ icol ] = unit;
        }
        String[] headings = new String[ ncol ];
        for ( int icol = 0; icol < ncol; icol++ ) {
            String heading = names[ icol ];
            String unit = units[ icol ];
            if ( hasUnits ) {
                heading += "<br>";
                if ( unit != null ){
                    heading += "(" + unit + ")";
                }
            }
            headings[ icol ] = heading;
        }
        outputRow( ostrm, "TH", null, names );
        if ( hasUnits ) {
            outputRow( ostrm, "TH", null, units );
        }

        /* Separator. */
        printLine( ostrm, "<TR><TD colspan='" + ncol + "'></TD></TR>" );

        /* Output the table data. */
        for ( RowSequence rseq = table.getRowSequence(); rseq.hasNext(); ) {
            rseq.next();
            Object[] row = rseq.getRow();
            String[] cells = new String[ ncol ];
            for ( int icol = 0; icol < ncol; icol++ ) {
                cells[ icol ] =
                    escape( colinfos[ icol ].formatValue( row[ icol ], 200 ) );
                if ( cells[ icol ].length() == 0 ) {
                    cells[ icol ] = "&nbsp;";
                }
            }
            outputRow( ostrm, "TD", null, cells );
        }

        /* Finish up. */
        printLine( ostrm, "</TABLE>" );
        ostrm.close();
    }

    /**
     * Outputs a row of header or data cells.
     * 
     * @param  ostrm  the stream for output
     * @param  tagname   the name of the element in which to wrap each
     *         cell ("TH" or "TD")
     * @param  attlist  any attributes to put on the elements
     * @param  values  the array of values providing cell contents
     */
    private void outputRow( OutputStream ostrm, String tagname, 
                                   String attlist, String[] values ) 
            throws IOException {
        int ncol = values.length;
        printLine( ostrm, "<TR>" );
        StringBuffer sbuf = new StringBuffer();
        for ( int icol = 0; icol < ncol; icol++ ) {
            sbuf.append( ' ' )
                .append( '<' )
                .append( tagname );
            if ( attlist != null ) {
                sbuf.append( " " + attlist );
            }
            sbuf.append( '>' );
            if ( values[ icol ] != null ) {
                sbuf.append( values[ icol ] );
            }
            sbuf.append( "</" )
                .append( tagname )
                .append( ">" );
        }
        printLine( ostrm, sbuf.toString() );
        printLine( ostrm, "</TR>" );
    }

    /**
     * Outputs a line of text, terminated by a newline, to a stream.
     *
     * @param   ostrm  output stream
     * @param   str   string to write
     */
    private void printLine( OutputStream ostrm, String str )
            throws IOException {
        ostrm.write( str.getBytes() );
        ostrm.write( (int) '\n' );
    }

    /**
     * Turns a string into a one suitable for inclusion in HTML text -
     * any special characters are escaped in an HTML-friendly fashion.
     *
     * @param   line  string to escape
     * @return   an HTML-friendly version of <tt>line</tt>
     */
    private String escape( String line ) {
        StringBuffer sbuf = new StringBuffer();
        for ( int i = 0; i < line.length(); i++ ) {
            char chr = line.charAt( i );
            switch ( chr ) {
                case '&':
                    sbuf.append( "&amp;" );
                    break;
                case '<':
                    sbuf.append( "&lt;" );
                    break;
                case '>':
                    sbuf.append( "&gt;" );
                    break;
                case '"':
                    sbuf.append( "&quot;" );
                    break;
                case '\'':
                    sbuf.append( "&apos;" );
                    break;
                default:
                    sbuf.append( ( chr > 0 && chr < 254 ) ? chr : '?' );
            }
        }
        return sbuf.toString();
    }

    /**
     * Turns a location string into a suitable output stream.
     *
     * @param  user-supplied location for the output file
     * @return  an output stream corresponding to <tt>location</tt>
     */
    private OutputStream getStream( String location )
            throws IOException {
        if ( location.equals( "-" ) ) {
            return System.out;
        }
        else {
            return new FileOutputStream( location );
        }
    }
}
