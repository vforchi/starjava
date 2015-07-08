package uk.ac.starlink.vo;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.CaretEvent;
import javax.swing.event.CaretListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.text.JTextComponent;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import uk.ac.starlink.table.gui.StarJTable;
import uk.ac.starlink.util.gui.ArrayTableColumn;
import uk.ac.starlink.util.gui.ArrayTableModel;
import uk.ac.starlink.util.gui.ArrayTableSorter;
import uk.ac.starlink.util.gui.ErrorDialog;
import uk.ac.starlink.util.gui.ShrinkWrapper;

/**
 * Displays the metadata from an array of SchemaMeta objects.
 * These can be acquired from a TableSet XML document as exposed
 * by VOSI and TAP interfaces or from interrogating TAP_SCHEMA tables.
 *
 * @author   Mark Taylor
 * @since    21 Jan 2011
 */
public class TableSetPanel extends JPanel {

    private final JTree tTree_;
    private final JTextField keywordField_;
    private final AndButton keyAndButt_;
    private final JCheckBox useNameButt_;
    private final JCheckBox useDescripButt_;
    private final TreeSelectionModel selectionModel_;
    private final JTable colTable_;
    private final JTable foreignTable_;
    private final ArrayTableModel colTableModel_;
    private final ArrayTableModel foreignTableModel_;
    private final MetaColumnModel colColModel_;
    private final MetaColumnModel foreignColModel_;
    private final ResourceMetaPanel servicePanel_;
    private final SchemaMetaPanel schemaPanel_;
    private final TableMetaPanel tablePanel_;
    private final JTabbedPane detailTabber_;
    private final int itabService_;
    private final int itabSchema_;
    private final int itabTable_;
    private final int itabCol_;
    private final int itabForeign_;
    private final JComponent treeContainer_;
    private TapServiceKit serviceKit_;
    private SchemaMeta[] schemas_;
    private ColumnMeta[] selectedColumns_;

    /**
     * Name of bound property for table selection.
     * Property value is the return value of {@link #getSelectedTable}.
     */
    public static final String TABLE_SELECTION_PROPERTY = "selectedTable";

    /**
     * Name of bound property for column list selection.
     * Property value is the return value of {@link #getSelectedColumns}.
     */
    public static final String COLUMNS_SELECTION_PROPERTY = "selectedColumns";

    /**
     * Name of bound property for schema array giving table metadata.
     * Property value is the return value of {@link #getSchemas}.
     */
    public static final String SCHEMAS_PROPERTY = "schemas";

    /** Number of nodes below which tree nodes are expanded. */
    private static final int TREE_EXPAND_THRESHOLD = 100;

    private static final Logger logger_ =
        Logger.getLogger( "uk.ac.starlink.vo" );

    /**
     * Constructor.
     *
     * @param  urlHandler  handles URLs that the user clicks on; may be null
     */
    public TableSetPanel( UrlHandler urlHandler ) {
        super( new BorderLayout() );
        tTree_ = new JTree();
        tTree_.setRootVisible( false );
        tTree_.setShowsRootHandles( true );
        tTree_.setExpandsSelectedPaths( true );
        tTree_.setCellRenderer( new CountTableTreeCellRenderer() );
        selectionModel_ = tTree_.getSelectionModel();
        selectionModel_.setSelectionMode( TreeSelectionModel
                                         .SINGLE_TREE_SELECTION );
        selectionModel_.addTreeSelectionListener( new TreeSelectionListener() {
            public void valueChanged( TreeSelectionEvent evt ) {
                updateForTableSelection();
                TableMeta oldSel =
                    TapMetaTreeModel.getTable( evt.getOldLeadSelectionPath() );
                TableMeta newSel =
                    TapMetaTreeModel.getTable( evt.getNewLeadSelectionPath() );
                assert newSel == getSelectedTable();
                TableSetPanel.this
                             .firePropertyChange( TABLE_SELECTION_PROPERTY,
                                                  oldSel, newSel );
            }
        } );

        keywordField_ = new JTextField();
        keywordField_.addCaretListener( new CaretListener() {
            public void caretUpdate( CaretEvent evt ) {
                updateTree( false );
            }
        } );
        JLabel keywordLabel = new JLabel( " Find: " );
        String keywordTip = "Enter one or more search terms to restrict "
                          + "the content of the metadata display tree";
        keywordField_.setToolTipText( keywordTip );
        keywordLabel.setToolTipText( keywordTip );

        keyAndButt_ = new AndButton( false );
        keyAndButt_.setMargin( new java.awt.Insets( 0, 0, 0, 0 ) );
        keyAndButt_.setToolTipText( "Choose to match either "
                                  + "all (And) or any (Or) "
                                  + "of the entered search terms "
                                  + "against table metadata" );
        useNameButt_ = new JCheckBox( "Name", true );
        useNameButt_.setToolTipText( "Select to match search terms against "
                                   + "table/schema names" );
        useDescripButt_ = new JCheckBox( "Descrip", false );
        useDescripButt_.setToolTipText( "Select to match search terms against "
                                      + "table/schema descriptions" );
        ActionListener findParamListener = new ActionListener() {
            public void actionPerformed( ActionEvent evt ) {
                if ( ! useNameButt_.isSelected() &&
                     ! useDescripButt_.isSelected() ) {
                    ( evt.getSource() == useNameButt_ ? useDescripButt_
                                                      : useNameButt_ )
                   .setSelected( true );
                }
                updateTree( false );
            }
        };
        keyAndButt_.addAndListener( findParamListener );
        useNameButt_.addActionListener( findParamListener );
        useDescripButt_.addActionListener( findParamListener );

        colTableModel_ = new ArrayTableModel( createColumnMetaColumns(),
                                              new ColumnMeta[ 0 ] );
        colTable_ = new JTable( colTableModel_ );
        colTable_.setColumnSelectionAllowed( false );
        colColModel_ =
            new MetaColumnModel( colTable_.getColumnModel(), colTableModel_ );
        colTable_.setColumnModel( colColModel_ );
        new ArrayTableSorter( colTableModel_ )
           .install( colTable_.getTableHeader() );
        ListSelectionModel colSelModel = colTable_.getSelectionModel();
        colSelModel.setSelectionMode( ListSelectionModel
                                     .MULTIPLE_INTERVAL_SELECTION );
        colSelModel.addListSelectionListener( new ListSelectionListener() {
            public void valueChanged( ListSelectionEvent evt ) {
                columnSelectionChanged();
            }
        } );
        selectedColumns_ = new ColumnMeta[ 0 ];

        foreignTableModel_ = new ArrayTableModel( createForeignMetaColumns(),
                                                  new ColumnMeta[ 0 ] );
        foreignTable_ = new JTable( foreignTableModel_ );
        foreignTable_.setColumnSelectionAllowed( false );
        foreignTable_.setRowSelectionAllowed( false );
        foreignColModel_ =
            new MetaColumnModel( foreignTable_.getColumnModel(),
                                 foreignTableModel_ );
        foreignTable_.setColumnModel( foreignColModel_ );
        new ArrayTableSorter( foreignTableModel_ )
           .install( foreignTable_.getTableHeader() );

        tablePanel_ = new TableMetaPanel();
        schemaPanel_ = new SchemaMetaPanel();
        servicePanel_ = new ResourceMetaPanel( urlHandler );

        detailTabber_ = new JTabbedPane();
        int itab = 0;
        detailTabber_.addTab( "Service", metaScroller( servicePanel_ ) );
        itabService_ = itab++;
        detailTabber_.addTab( "Schema", metaScroller( schemaPanel_ ) );
        itabSchema_ = itab++;
        detailTabber_.addTab( "Table", metaScroller( tablePanel_ ) );
        itabTable_ = itab++;
        detailTabber_.addTab( "Columns", new JScrollPane( colTable_ ) );
        itabCol_ = itab++;
        detailTabber_.addTab( "Foreign Keys",
                              new JScrollPane( foreignTable_ ) );
        itabForeign_ = itab++;
        detailTabber_.setSelectedIndex( itabSchema_ );

        final JComponent findParamLine = Box.createHorizontalBox();
        JComponent treePanel = new JPanel( new BorderLayout() ) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension( findParamLine.getPreferredSize().width
                                      + 20,
                                      super.getPreferredSize().height );
            }
        };
        treeContainer_ = new JPanel( new BorderLayout() );
        treeContainer_.add( new JScrollPane( tTree_ ), BorderLayout.CENTER );
        treePanel.add( treeContainer_, BorderLayout.CENTER );
        JComponent keywordLine = Box.createHorizontalBox();
        keywordLine.add( keywordLabel );
        keywordLine.add( keywordField_ );
        findParamLine.add( useNameButt_ );
        findParamLine.add( useDescripButt_ );
        findParamLine.add( Box.createHorizontalGlue() );
        findParamLine.add( keyAndButt_ );
        JComponent findBox = Box.createVerticalBox();
        findBox.add( keywordLine );
        findBox.add( findParamLine );
        findBox.setBorder( BorderFactory.createEmptyBorder( 0, 0, 5, 5 ) );
        treePanel.add( findBox, BorderLayout.NORTH );

        JSplitPane metaSplitter = new JSplitPane( JSplitPane.HORIZONTAL_SPLIT );
        metaSplitter.setBorder( BorderFactory.createEmptyBorder() );
        treePanel.setMinimumSize( new Dimension( 100, 100 ) );
        detailTabber_.setMinimumSize( new Dimension( 100, 100 ) );
        metaSplitter.setLeftComponent( treePanel );
        metaSplitter.setRightComponent( detailTabber_ );
        add( metaSplitter, BorderLayout.CENTER );

        setSchemas( null );
    }

    /**
     * Returns a new menu for controlling which columns are visible in
     * the column display table.
     *
     * @param  name  menu name
     */
    public JMenu makeColumnDisplayMenu( String name ) {
        return colColModel_.makeCheckBoxMenu( name );
    }

    /**
     * Installs an object that knows how to acquire TAP service metadata.
     * If the supplied kit is non-null, calling this method
     * initiates asynchronous reads of metadata, which will be displayed
     * in this panel when it arrives.
     *
     * @param  serviceKit  TAP service metadata access kit
     */
    public void setServiceKit( final TapServiceKit serviceKit ) {
        if ( serviceKit_ != null ) {
            serviceKit_.shutdown();
        }
        serviceKit_ = serviceKit;
        setSchemas( null );
        setResourceInfo( null );
        if ( serviceKit == null ) {
            servicePanel_.setId( null, null );
        }
        else {
            final String ivoid = serviceKit.getIvoid();
            servicePanel_.setId( serviceKit.getServiceUrl(), ivoid );
            serviceKit.acquireResource(
                           new ResultHandler<Map<String,String>>() {
                public boolean isActive() {
                    return serviceKit == serviceKit_;
                }
                public void showWaiting() {
                    logger_.info( "Reading resource record for " + ivoid );
                }
                public void showResult( Map<String,String> resourceMap ) {
                    setResourceInfo( resourceMap );
                }
                public void showError( IOException error ) {
                    setResourceInfo( null );
                }
            } );
            serviceKit.acquireRoles( new ResultHandler<RegRole[]>() {
                public boolean isActive() {
                    return serviceKit == serviceKit_;
                }
                public void showWaiting() {
                    logger_.info( "Reading res_role records for " + ivoid );
                }
                public void showResult( RegRole[] roles ) {
                    setResourceRoles( roles );
                }
                public void showError( IOException error ) {
                    setResourceRoles( null );
                }
            } );
            serviceKit.acquireSchemas( new ResultHandler<SchemaMeta[]>() {
                private JProgressBar progBar;
                public boolean isActive() {
                    return serviceKit == serviceKit_;
                }
                public void showWaiting() {
                    logger_.info( "Reading up-front table metadata" );
                    progBar = showFetchProgressBar();
                }
                public void showResult( SchemaMeta[] result ) {
                    stopProgress();
                    logger_.info( "Read " + getMetaDescrip() );
                    setSchemas( result );
                }
                public void showError( IOException error ) {
                    stopProgress();
                    logger_.log( Level.WARNING,
                                 "Error reading " + getMetaDescrip(), error );
                    showFetchFailure( error, serviceKit.getMetaReader() );
                }
                private void stopProgress() {
                    if ( progBar != null ) {
                        progBar.setIndeterminate( false );
                        progBar.setValue( 0 );
                        progBar = null;
                    }
                }
                private String getMetaDescrip() {
                    TapMetaReader metaRdr = serviceKit.getMetaReader();
                    StringBuffer sbuf = new StringBuffer()
                        .append( "up-front table metadata" );
                    if ( metaRdr != null ) {
                        sbuf.append( " from " )
                            .append( metaRdr.getSource() )
                            .append( " using " )
                            .append( metaRdr.getMeans() );
                    }
                    return sbuf.toString();
                }
            } );
        }
    }

    /**
     * Returns the object currently responsible for acquiring table metadata.
     *
     * @return  metadata access kit, may be null
     */
    public TapServiceKit getServiceKit() {
        return serviceKit_;
    }

    /**
     * Returns the current table metadata set.
     * May be null if the read is still in progress.
     *
     * @return   current schema metadata array, may be null
     */
    public SchemaMeta[] getSchemas() {
        return schemas_;
    }

    /**
     * Sets the data model for the metadata displayed by this panel,
     * and updates the display.
     * The data is in the form of an array of schema metadata objects.
     *
     * @param  schemas  schema metadata objects, null if no metadata available
     */
    private void setSchemas( SchemaMeta[] schemas ) {
        if ( schemas != null ) {
            checkSchemasPopulated( schemas );
        }
        SchemaMeta[] oldSchemas = schemas_;
        schemas_ = schemas;
        TreeModel treeModel =
            new TapMetaTreeModel( schemas_ == null ? new SchemaMeta[ 0 ]
                                                   : schemas_ );
        tTree_.setModel( new MaskTreeModel( treeModel, true ) );
        keywordField_.setText( null );
        selectionModel_.setSelectionPath( null );
        updateTree( true );

        final String countTxt;
        if ( schemas == null ) {
            countTxt = "no metadata";
        }
        else {
            int nTable = 0;
            for ( SchemaMeta schema : schemas ) {
                nTable += schema.getTables().length;
            }
            countTxt = schemas.length + " schemas, " + nTable + " tables";
        }
        servicePanel_.setSize( countTxt );
        replaceTreeComponent( null );
        updateForTableSelection();
        firePropertyChange( SCHEMAS_PROPERTY, oldSchemas, schemas );
        repaint();
    }

    /**
     * Sets the TapCapability information to be displayed in this panel.
     *
     * @param   capability   current capability object, may be null
     */
    public void setCapability( TapCapability capability ) {
        servicePanel_.setCapability( capability );
    }

    /**
     * Sets whether an examples document is known to be available
     * in the standard location (&lt;serviceUrl&gt;/examples).
     *
     * @param   hasExamples  true iff examples are known to exist
     */
    public void setHasExamples( boolean hasExamples ) {
        String exampleUrl = hasExamples && serviceKit_ != null
                          ? serviceKit_.getServiceUrl() + "/examples"
                          : null;
        servicePanel_.setExamplesUrl( exampleUrl );
    }

    /**
     * Displays information about the registry resource corresponding to
     * the TAP service represented by this panel.
     * The argument is a map of standard RegTAP resource column names
     * to their values.
     *
     * @param  map  map of service resource metadata items,
     *              or null for no info
     */
    private void setResourceInfo( Map<String,String> map ) {
        servicePanel_.setResourceInfo( map == null
                                     ? new HashMap<String,String>()
                                     : map );
        detailTabber_.setIconAt( itabService_, activeIcon( map != null ) );
    }

    /**
     * Displays information about registry resource roles corresponding
     * to the TAP services represented by this panel.
     *
     * @param  roles  list of known roles, or null for no info
     */
    private void setResourceRoles( RegRole[] roles ) {
        servicePanel_.setResourceRoles( roles == null ? new RegRole[ 0 ]
                                                      : roles );
    }

    /**
     * Displays a progress bar to indicate that metadata fetching is going on.
     *
     * @return  the progress bar component
     */
    private JProgressBar showFetchProgressBar() {
        JProgressBar progBar = new JProgressBar();
        progBar.setIndeterminate( true );
        JComponent progLine = Box.createHorizontalBox();
        progLine.add( Box.createHorizontalGlue() );
        progLine.add( progBar );
        progLine.add( Box.createHorizontalGlue() );
        JComponent workBox = Box.createVerticalBox();
        workBox.add( Box.createVerticalGlue() );
        workBox.add( createLabelLine( "Fetching table metadata" ) );
        workBox.add( Box.createVerticalStrut( 5 ) );
        workBox.add( progLine );
        workBox.add( Box.createVerticalGlue() );
        JComponent workPanel = new JPanel( new BorderLayout() );
        workPanel.setBackground( UIManager.getColor( "Tree.background" ) );
        workPanel.setBorder( BorderFactory.createEtchedBorder() );
        workPanel.add( workBox, BorderLayout.CENTER );
        replaceTreeComponent( workPanel );
        return progBar;
    }

    /**
     * Displays an indication that metadata fetching failed.
     * 
     * @param  error   error that caused the failure
     * @param  metaReader   metadata reader
     */
    private void showFetchFailure( Throwable error, TapMetaReader metaReader ) {

        /* Pop up an error dialog. */
        List<String> msgList = new ArrayList<String>();
        msgList.add( "Error reading TAP service table metadata" );
        if ( metaReader != null ) {
            msgList.add( "Method: " + metaReader.getMeans() );
            msgList.add( "Source: " + metaReader.getSource() );
        }
        String[] msgLines = msgList.toArray( new String[ 0 ] );
        ErrorDialog.showError( this, "Table Metadata Error", error, msgLines );

        /* Prepare a component describing what went wrong. */
        JComponent errLine = Box.createHorizontalBox();
        errLine.setAlignmentX( 0 );
        errLine.add( new JLabel( "Error: " ) );
        String errtxt = error.getMessage();
        if ( errtxt == null || errtxt.trim().length() == 0 ) {
            errtxt = error.toString();
        }
        JTextField errField = new JTextField( errtxt );
        errField.setEditable( false );
        errField.setBorder( BorderFactory.createEmptyBorder() );
        errLine.add( new ShrinkWrapper( errField ) );
        JComponent linesBox = Box.createVerticalBox();
        linesBox.add( Box.createVerticalGlue() );
        linesBox.add( createLabelLine( "No table metadata" ) );
        linesBox.add( Box.createVerticalStrut( 15 ) );
        for ( String line : msgLines ) {
            linesBox.add( createLabelLine( line ) );
        }
        linesBox.add( Box.createVerticalStrut( 15 ) );
        linesBox.add( errLine );
        linesBox.add( Box.createVerticalGlue() );
        JComponent panel = new JPanel( new BorderLayout() );
        panel.add( linesBox, BorderLayout.CENTER );
        JScrollPane scroller = new JScrollPane( panel );

        /* Post it in place of the metadata jtree display. */
        replaceTreeComponent( scroller );
    }

    /**
     * Returns a component containing some text, suitable for adding to
     * a list of text lines.
     *
     * @param  text  content
     * @return  jlabel
     */
    private JComponent createLabelLine( String text ) {
        JLabel label = new JLabel( text );
        label.setAlignmentX( 0 );
        return label;
    }

    /**
     * Places a component where the schema metadata JTree normally goes.
     * If the supplied component is null, the tree is put back.
     *
     * @param  content  component to replace tree, or null
     */
    private void replaceTreeComponent( JComponent content ) {
        treeContainer_.removeAll();
        treeContainer_.add( content != null ? content
                                            : new JScrollPane( tTree_ ),
                            BorderLayout.CENTER );
    }

    /**
     * Checks that all the schemas are populated with lists of their tables.
     * The SchemaMeta and TapMetaReader interface permit unpopulated schemas,
     * but this GUI relies in some places on the assumption that schemas
     * are always populated, so things will probably go wrong if it's not
     * the case.  Log a warning in case of unpopulated schemas
     * to give a clue what's wrong.
     *
     * @param  schemas  schemas to test
     */
    private void checkSchemasPopulated( SchemaMeta[] schemas ) {
        for ( SchemaMeta smeta : schemas ) {
            if ( smeta.getTables() == null ) {
                logger_.warning( "Schema metadata object(s) not populated"
                               + " with tables, probably will cause trouble"
                               + "; use a different TapMetaReader?" );
                return;
            }
        }
    }

    /**
     * Returns the table which is currently selected for metadata display.
     *
     * @return   selected table, may be null
     */
    public TableMeta getSelectedTable() {
        return TapMetaTreeModel.getTable( selectionModel_.getSelectionPath() );
    }

    /**
     * Returns an array of the columns which are currently selected in
     * the column metadata display table.
     *
     * @return   array of selected columns, may be empty but not null
     */
    public ColumnMeta[] getSelectedColumns() {
        return selectedColumns_;
    }

    /**
     * Invoked when table selection may have changed.
     */
    private void updateForTableSelection() {
        TreePath path = selectionModel_.getSelectionPath();
        final TableMeta table = TapMetaTreeModel.getTable( path );
        SchemaMeta schema = TapMetaTreeModel.getSchema( path );
        if ( table == null ||
             ! serviceKit_.onColumns( table, new Runnable() {
            public void run() {
                if ( table == getSelectedTable() ) {
                    ColumnMeta[] cols = table.getColumns();
                    displayColumns( table, cols );
                    tablePanel_.setColumns( cols );
                }
            }
        } ) ) {
            displayColumns( table, new ColumnMeta[ 0 ] );
        }
        if ( table == null ||
             ! serviceKit_.onForeignKeys( table, new Runnable() {
            public void run() {
                if ( table == getSelectedTable() ) {
                    ForeignMeta[] fkeys = table.getForeignKeys();
                    displayForeignKeys( table, fkeys );
                    tablePanel_.setForeignKeys( fkeys );
                }
            }
        } ) ) {
            displayForeignKeys( table, new ForeignMeta[ 0 ] );
        }
        schemaPanel_.setSchema( schema );
        detailTabber_.setIconAt( itabSchema_, activeIcon( schema != null ) );
        tablePanel_.setTable( table );
        detailTabber_.setIconAt( itabTable_, activeIcon( table != null ) );
    }

    /**
     * Invoked when the column selection may have changed.
     */
    private void columnSelectionChanged() {
        ColumnMeta[] oldCols = selectedColumns_;

        /* Get a list of all the columns in the current selection. */
        Collection<ColumnMeta> curSet = new HashSet<ColumnMeta>();
        ListSelectionModel selModel = colTable_.getSelectionModel();
        if ( ! selModel.isSelectionEmpty() ) {
            int imin = selModel.getMinSelectionIndex();
            int imax = selModel.getMaxSelectionIndex();
            for ( int i = imin; i <= imax; i++ ) {
                if ( selModel.isSelectedIndex( i ) ) {
                    curSet.add( (ColumnMeta) colTableModel_.getItems()[ i ] );
                }
            }
        }

        /* Prepare a list with the same content as this selection list,
         * but following the sequence of the elements in the previous
         * version of the list as much as possible.
         * This could be done a bit more elegantly with collections
         * if ColumnMeta implemented object equality properly,
         * but it doesn't. */
        List<ColumnMeta> newList = new ArrayList<ColumnMeta>();
        for ( ColumnMeta col : oldCols ) {
            ColumnMeta oldCol = getNamedEntry( curSet, col.getName() );
            if ( oldCol != null ) {
                newList.add( oldCol );
            }
        }
        for ( ColumnMeta col : curSet ) {
            if ( getNamedEntry( newList, col.getName() ) == null ) {
                newList.add( col );
            }
        }
        assert new HashSet<ColumnMeta>( newList ).equals( curSet );
        selectedColumns_ = newList.toArray( new ColumnMeta[ 0 ] );

        /* Notify listeners if required. */
        if ( ! Arrays.equals( selectedColumns_, oldCols ) ) {
            firePropertyChange( COLUMNS_SELECTION_PROPERTY,
                                oldCols, selectedColumns_ );
        }
    }

    /**
     * Updates the display if required for the columns of a table.
     *
     * @param  table  table
     * @param  cols  columns
     */
    private void displayColumns( TableMeta table, ColumnMeta[] cols ) {
        assert table == getSelectedTable();
        colTableModel_.setItems( cols );
        detailTabber_.setIconAt( itabCol_, activeIcon( cols != null &&
                                                       cols.length > 0 ) );
        if ( table != null ) {
            configureColumnWidths( colTable_ );
        }
        columnSelectionChanged();
    }

    /**
     * Updates the display if required for the foreign keys of a table.
     *
     * @param  table  table
     * @param  fkeys  foreign keys
     */
    private void displayForeignKeys( TableMeta table, ForeignMeta[] fkeys ) {
        assert table == getSelectedTable();
        foreignTableModel_.setItems( fkeys );
        detailTabber_.setIconAt( itabForeign_,
                                 activeIcon( fkeys != null &&
                                             fkeys.length > 0 ) );
        if ( table != null ) {
            configureColumnWidths( foreignTable_ );
        }
    }

    /**
     * Configures the columns widths of a JTable in the tabbed pane
     * to correspond to its current contents.
     *
     * @param  jtable   table to update
     */
    private void configureColumnWidths( final JTable jtable ) {
        Runnable configer = new Runnable() {
            public void run() {
                StarJTable.configureColumnWidths( jtable, 360, 9999 );
            }
        };
        if ( detailTabber_.getSize().width > 0 ) {
            configer.run();
        }
        else {
            SwingUtilities.invokeLater( configer );
        }
    }

    /**
     * Called if the schema information in the JTree or its presentation
     * rules may have changed.
     *
     * @param  dataChanged  true iff this update includes a change of
     *         the schema array underlying the tree model
     */
    private void updateTree( boolean dataChanged ) {

        /* We should have a MaskTreeModel, unless maybe there's no data. */
        TreeModel treeModel = tTree_.getModel();
        if ( ! ( treeModel instanceof MaskTreeModel ) ) {
            return;
        }
        MaskTreeModel mModel = (MaskTreeModel) treeModel;

        /* Get a node mask object from the GUI components. */
        final MaskTreeModel.Mask mask;
        String text = keywordField_.getText();
        if ( text == null || text.trim().length() == 0 ) {
            mask = null;
        }
        else {
            String[] searchTerms = text.split( "\\s+" );
            assert searchTerms.length > 0;
            boolean isAnd = keyAndButt_.isAnd();
            boolean useName = useNameButt_.isSelected();
            boolean useDescrip = useDescripButt_.isSelected();
            NodeStringer stringer =
                NodeStringer.createInstance( useName, useDescrip );
            mask = new WordMatchMask( searchTerms, stringer, isAnd );
        }

        /* We will be changing the mask, which will cause a
         * treeStructureChanged TreeModelEvent to be sent to listeners,
         * more or less wiping out any state of the JTree view.
         * So store the view state we want to preserve (information about
         * selections and node expansion) here, so we can restore it after
         * the model has changed. */
        Object root = mModel.getRoot();
        TreePath[] selections = tTree_.getSelectionPaths();
        List<TreePath> expandedList = new ArrayList<TreePath>();
        for ( Enumeration<TreePath> tpEn =
                  tTree_.getExpandedDescendants( new TreePath( root ) );
              tpEn.hasMoreElements(); ) {
            expandedList.add( tpEn.nextElement() );
        }
        TreePath[] oldExpanded = expandedList.toArray( new TreePath[ 0 ] );
        int oldCount = mModel.getNodeCount();

        /* Update the model. */
        mModel.setMask( mask );

        /* Apply node expansions in the JTree view.  This is a bit ad hoc.
         * If we've just cut the tree down from huge to manageable,
         * expand all the top-level (schema) nodes.  Conversely,
         * if we've just grown it from manageable to huge, collapse
         * all the top-level nodes.  Otherwise, try to retain (restore)
         * the previous expansion state.  */
        int newCount = mModel.getNodeCount();
        int ne = TREE_EXPAND_THRESHOLD;
        final TreePath[] newExpanded;
        if ( ( dataChanged || oldCount > ne ) && newCount < ne ) {
            int nc = mModel.getChildCount( root );
            newExpanded = new TreePath[ nc ];
            for ( int ic = 0; ic < nc; ic++ ) {
                Object child = mModel.getChild( root, ic );
                newExpanded[ ic ] =
                    new TreePath( new Object[] { root, child } );
            }
        }
        else if ( dataChanged || ( oldCount < ne && newCount > ne ) ) {
            newExpanded = new TreePath[ 0 ];
        }
        else {
            newExpanded = oldExpanded;
        }
        for ( TreePath expTp : newExpanded ) {
            tTree_.expandPath( expTp );
        }

        /* Try to restore previous selections (only one, probably).
         * If the old selections are no longer in the tree, chuck them out. */
        if ( mask != null && selections != null ) {
            selections = sanitiseSelections( selections, mModel );
        }

        /* If for whatever reason we have no selections, select something.
         * This logic will probably pick the first leaf node (table metadata,
         * rather than schema metadata).  But if it can't find it where it
         * expects, it will just pick the very first node in the tree. */
        if ( selections == null || selections.length == 0 ) {
            TreePath tp0 = tTree_.getPathForRow( 0 );
            TreePath tp1 = tTree_.getPathForRow( 1 );
            TreePath tp = tp1 != null && tp0 != null
                       && mModel.isLeaf( tp1.getLastPathComponent() )
                       && ! mModel.isLeaf( tp0.getLastPathComponent() )
                     ? tp1
                     : tp0;
            selections = tp != null ? new TreePath[] { tp }
                                    : new TreePath[ 0 ];
        }
        tTree_.setSelectionPaths( selections );
    }

    /**
     * Returns the first column in a given list with a given name,
     * or null if there is no such entry.
     *
     * @param  list  column list
     * @param  name  required name
     * @return   list entry with given name, or null
     */
    private static ColumnMeta getNamedEntry( Collection<ColumnMeta> list,
                                             String name ) {
        if ( name != null ) {
            for ( ColumnMeta c : list ) {
                if ( name.equals( c.getName() ) ) {
                    return c;
                }
            }
        }
        return null;
    }

    /**
     * Get a list of tree paths based on a given list, but making sure
     * they exist in a given tree model.
     *
     * @param   selections  initial selection paths
     * @param   model   tree model within which result selections must exist
     * @return  list of selections that are in the model (may be empty)
     */
    private static TreePath[] sanitiseSelections( TreePath[] selections,
                                                  TreeModel model ) {
        List<TreePath> okPaths = new ArrayList<TreePath>();
        Object root = model.getRoot();

        /* Tackle each input path one at a time. */
        for ( TreePath path : selections ) {
            if ( path.getPathComponent( 0 ) != root ) {
                assert false;
            }
            else {

                /* Try to add each element of the input path to the result path.
                 * If any of the nodes in the chain is missing, just stop
                 * there, leaving an ancestor of the input path. */
                int nel = path.getPathCount();
                List<Object> els = new ArrayList<Object>( nel );
                els.add( root );
                for ( int i = 1; i < nel; i++ ) {
                    Object pathEl = path.getPathComponent( i );
                    if ( model.getIndexOfChild( els.get( i - 1 ), pathEl )
                         >= 0 ) {
                        els.add( pathEl );
                    }
                    else {
                        break;
                    }
                }

                /* Add it to the result, but only if it's not just the
                 * root node (and if we don't already have it). */
                TreePath okPath =
                    new TreePath( els.toArray( new Object[ 0 ] ) );
                if ( okPath.getPathCount() > 1 &&
                     ! okPaths.contains( okPath ) ) {
                    okPaths.add( okPath );
                }
            }
        }
        return okPaths.toArray( new TreePath[ 0 ] );
    }

    /**
     * Returns a small icon indicating whether a given tab is currently
     * active or not.
     *
     * @param   isActive  true iff tab has content
     * @return  little icon
     */
    private Icon activeIcon( boolean isActive ) {
        return HasContentIcon.getIcon( isActive );
    }

    /**
     * Returns the ColumnMeta object associated with a given item
     * in the column metadata table model.  It's just a cast.
     *
     * @param   item  table cell contents
     * @return   column metadata object associated with <code>item</code>
     */
    private static ColumnMeta getCol( Object item ) {
        return (ColumnMeta) item;
    }

    /**
     * Returns the ForeignMeta object associated with a given item
     * in the foreign key table model.  It's just a cast.
     *
     * @param  item   table cell contents
     * @return   foreign key object associated with <code>item</code>
     */
    private static ForeignMeta getForeign( Object item ) {
        return (ForeignMeta) item;
    }

    /**
     * Utility method to return a string representing the length of an array.
     *
     * @param  array  array object, or null
     * @return  string giving length of array, or null for null input
     */
    private static String arrayLength( Object[] array ) {
        return array == null ? null : Integer.toString( array.length );
    }

    /**
     * Constructs an array of columns which define the table model
     * to use for displaying the column metadata.
     *
     * @return   column descriptions
     */
    private static ArrayTableColumn[] createColumnMetaColumns() {
        return new ArrayTableColumn[] {
            new ArrayTableColumn( "Name", String.class ) {
                public Object getValue( Object item ) {
                    return getCol( item ).getName();
                }
            },
            new ArrayTableColumn( "DataType", String.class ) {
                public Object getValue( Object item ) {
                    return getCol( item ).getDataType();
                }
            },
            new ArrayTableColumn( "Indexed", Boolean.class ) {
                public Object getValue( Object item ) {
                    return Boolean
                          .valueOf( Arrays.asList( getCol( item ).getFlags() )
                                          .indexOf( "indexed" ) >= 0 );
                }
            },
            new ArrayTableColumn( "Unit", String.class ) {
                public Object getValue( Object item ) {
                    return getCol( item ).getUnit();
                }
            },
            new ArrayTableColumn( "Description", String.class ) {
                public Object getValue( Object item ) {
                    return getCol( item ).getDescription();
                }
            },
            new ArrayTableColumn( "UCD", String.class ) {
                public Object getValue( Object item ) {
                    return getCol( item ).getUcd();
                }
            },
            new ArrayTableColumn( "Utype", String.class ) {
                public Object getValue( Object item ) {
                    return getCol( item ).getUtype();
                }
            },
            new ArrayTableColumn( "Flags", String.class ) {
                public Object getValue( Object item ) {
                    String[] flags = getCol( item ).getFlags();
                    if ( flags != null && flags.length > 0 ) {
                        StringBuffer sbuf = new StringBuffer();
                        for ( int i = 0; i < flags.length; i++ ) {
                            if ( i > 0 ) {
                                sbuf.append( ' ' );
                            }
                            sbuf.append( flags[ i ] );
                        }
                        return sbuf.toString();
                    }
                    else {
                        return null;
                    }
                }
            },
        };
    }

    /**
     * Constructs an array of columns which define the table model
     * to use for displaying foreign key information.
     *
     * @return  column descriptions
     */
    private static ArrayTableColumn[] createForeignMetaColumns() {
        return new ArrayTableColumn[] {
            new ArrayTableColumn( "Target Table", String.class ) {
                public Object getValue( Object item ) {
                    return getForeign( item ).getTargetTable();
                }
            },
            new ArrayTableColumn( "Links", String.class ) {
                public Object getValue( Object item ) {
                    ForeignMeta.Link[] links = getForeign( item ).getLinks();
                    StringBuffer sbuf = new StringBuffer();
                    for ( int i = 0; i < links.length; i++ ) {
                        ForeignMeta.Link link = links[ i ];
                        if ( i > 0 ) {
                            sbuf.append( "; " );
                        }
                        sbuf.append( link.getFrom() )
                            .append( "->" )
                            .append( link.getTarget() );
                    }
                    return sbuf.toString();
                }
            },
            new ArrayTableColumn( "Description", String.class ) {
                public Object getValue( Object item ) {
                    return getForeign( item ).getDescription();
                }
            },
            new ArrayTableColumn( "Utype", String.class ) {
                public Object getValue( Object item ) {
                    return getForeign( item ).getUtype();
                }
            },
        };
    }

    /**
     * Wraps a MetaPanel in a suitable JScrollPane.
     *
     * @param  panel  panel to wrap
     * @return   wrapped panel
     */
    private static JScrollPane metaScroller( MetaPanel panel ) {
        return new JScrollPane( panel, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                                       JScrollPane.HORIZONTAL_SCROLLBAR_NEVER );
    }

    /**
     * TreeCellRenderer that appends a count of table children to the
     * text label for each schema entry in the tree.
     */
    private static class CountTableTreeCellRenderer
            extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent( JTree tree, Object value,
                                                       boolean isSelected,
                                                       boolean isExpanded,
                                                       boolean isLeaf, int irow,
                                                       boolean hasFocus ) {
            Component comp =
                super.getTreeCellRendererComponent( tree, value, isSelected,
                                                    isExpanded, isLeaf, irow,
                                                    hasFocus );
            if ( value instanceof SchemaMeta ) {
                TableMeta[] tables = ((SchemaMeta) value).getTables();
                if ( tables != null ) {
                    int ntTotal = tables.length;
                    TreeModel model = tree.getModel();
                    int ntPresent =
                        model.isLeaf( value ) ? -1
                                              : model.getChildCount( value );
                    boolean hasMask = model instanceof MaskTreeModel
                                   && ((MaskTreeModel) model).getMask() != null;
                    StringBuffer sbuf = new StringBuffer();
                    sbuf.append( getText() )
                        .append( " (" );
                    if ( hasMask ) {
                        sbuf.append( ntPresent )
                            .append( "/" );
                    }
                    sbuf.append( ntTotal )
                        .append( ")" );
                    setText( sbuf.toString() );
                }
            }
            return comp;
        }
    }

    /**
     * Extracts text elements from tree nodes for comparison with search terms.
     */
    private static abstract class NodeStringer {
        private final boolean useName_;
        private final boolean useDescription_;

        /**
         * Constructor.
         *
         * @param  useName  true to use the node name as one of the strings
         * @param  useDescription  true to use the node description as one
         *                         of the strings
         */
        private NodeStringer( boolean useName, boolean useDescription ) {
            useName_ = useName;
            useDescription_ = useDescription;
        }

        /**
         * Supplies a list of strings that characterise a given tree node.
         *
         * @param  node  tree node
         * @return   list of strings associated with the node
         */
        public abstract List<String> getStrings( Object node );

        @Override
        public int hashCode() {
            int code = 5523;
            code = 23 * code + ( useName_ ? 11 : 13 );
            code = 23 * code + ( useDescription_ ? 23 : 29 );
            return code;
        }

        @Override
        public boolean equals( Object o ) {
            if ( o instanceof NodeStringer ) {
                NodeStringer other = (NodeStringer) o;
                return this.useName_ == other.useName_
                    && this.useDescription_ == other.useDescription_;
            }
            else {
                return false;
            }
        }

        /**
         * Constructs an instance of this class.
         *
         * @param  useName  true to use the node name as one of the strings
         * @param  useDescrip  true to use the node description as
         *                     one of the strings
         */
        public static NodeStringer createInstance( final boolean useName,
                                                   final boolean useDescrip ) {

            /* Treat name only as a special case for efficiency. */
            if ( useName && ! useDescrip ) {
                final List<String> emptyList = Arrays.asList( new String[ 0 ] );
                return new NodeStringer( useName, useDescrip ) {
                    public List<String> getStrings( Object node ) {
                        return node == null
                             ? emptyList
                             : Collections.singletonList( node.toString() );
                    }
                };
            }

            /* Otherwise treat it more generally. */
            else {
                return new NodeStringer( useName, useDescrip ) {
                    public List<String> getStrings( Object node ) {
                        List<String> list = new ArrayList<String>();
                        if ( node != null ) {
                            if ( useName ) {
                                list.add( node.toString() );
                            }
                            if ( useDescrip ) {
                                String descrip = null;
                                if ( node instanceof TableMeta ) {
                                    descrip =
                                        ((TableMeta) node).getDescription();
                                }
                                else if ( node instanceof SchemaMeta ) {
                                    descrip =
                                        ((SchemaMeta) node).getDescription();
                                }
                                if ( descrip != null ) {
                                    list.add( descrip );
                                }
                            }
                        }
                        return list;
                    }
                };
            }
        }
    }

    /**
     * Tree node mask that selects on simple matches of node name strings
     * to one or more space-separated words entered in the search field.
     *
     * <p>Implements equals/hashCode for equality, which isn't essential,
     * but somewhat beneficial for efficiency.
     */
    private static class WordMatchMask implements MaskTreeModel.Mask {
        private final Set<String> lwords_;
        private final NodeStringer stringer_;
        private final boolean isAnd_;

        /**
         * Constructor.
         *
         * @param  words   search terms
         * @param  stringer  converts node to text strings for matching
         * @param  isAnd  true to require matching of a node string against
         *                all search terms, false to match against any
         */
        WordMatchMask( String[] words, NodeStringer stringer, boolean isAnd ) {
            lwords_ = new HashSet<String>( words.length );
            for ( String word : words ) {
                lwords_.add( word.toLowerCase() );
            }
            stringer_ = stringer;
            isAnd_ = isAnd;
        }

        public boolean isIncluded( Object node ) {
            for ( String nodeTxt : stringer_.getStrings( node ) ) {
                if ( nodeTxt != null ) {
                    String nodetxt = nodeTxt.toLowerCase();
                    if ( isAnd_ ? matchesAllWords( nodetxt )
                                : matchesAnyWord( nodetxt ) ) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Tests whether a given string matches any of this mask's search terms.
         *
         * @param  txt  test string
         * @return  true iff txt matches any search term
         */
        private boolean matchesAnyWord( String txt ) {
            for ( String lword : lwords_ ) {
                if ( txt.indexOf( lword ) >= 0 ) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Tests whether a given string matches all of this mask's search terms.
         *
         * @param  txt  test string
         * @return   true iff txt matches all search terms
         */
        private boolean matchesAllWords( String txt ) {
            for ( String lword : lwords_ ) {
                if ( txt.indexOf( lword ) < 0 ) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            int code = 324223;
            code = 23 * code + lwords_.hashCode();
            code = 23 * code + stringer_.hashCode();
            code = 23 * code + ( isAnd_ ? 11 : 17 );
            return code;
        }

        @Override
        public boolean equals( Object o ) {
            if ( o instanceof WordMatchMask ) {
                WordMatchMask other = (WordMatchMask) o;
                return this.lwords_.equals( other.lwords_ )
                    && this.stringer_.equals( other.stringer_ )
                    && this.isAnd_ == other.isAnd_;
            }
            else {
                return false;
            }
        }
    }

    /**
     * MetaPanel subclass for displaying table metadata.
     */
    private static class TableMetaPanel extends MetaPanel {
        private final JTextComponent nameField_;
        private final JTextComponent ncolField_;
        private final JTextComponent nfkField_;
        private final JTextComponent descripField_;
        private TableMeta table_;

        /**
         * Constructor.
         */
        TableMetaPanel() {
            nameField_ = addLineField( "Name" );
            ncolField_ = addLineField( "Columns" );
            nfkField_ = addLineField( "Foreign Keys" );
            descripField_ = addMultiLineField( "Description" );
        }

        /**
         * Configures this component to display metadata for a given table.
         *
         * @param  table  table metadata to display
         */
        public void setTable( TableMeta table ) {
            if ( table != table_ ) {
                table_ = table;
                setFieldText( nameField_,
                              table == null ? null : table.getName() );
                setFieldText( descripField_,
                              table == null ? null : table.getDescription() );
                setColumns( table == null ? null : table.getColumns() );
                setForeignKeys( table == null ? null : table.getForeignKeys() );
            }
        }

        /**
         * Informs this panel of the column list for the currently displayed
         * table.  Only the array size is used.
         *
         * @param  cols  column array, or null
         */
        public void setColumns( ColumnMeta[] cols ) {
            setFieldText( ncolField_, arrayLength( cols ) );
        }

        /**
         * Informs this panel of the foreign key list for the currently
         * displayed table.  Only the array size is used.
         *
         * @param   fkeys  foreign key array, or null
         */
        public void setForeignKeys( ForeignMeta[] fkeys ) {
            setFieldText( nfkField_, arrayLength( fkeys ) );
        }
    }

    /**
     * MetaPanel subclass for displaying schema metadata.
     */
    private static class SchemaMetaPanel extends MetaPanel {
        private final JTextComponent nameField_;
        private final JTextComponent ntableField_;
        private final JTextComponent descripField_;
        private SchemaMeta schema_;

        /**
         * Constructor.
         */
        SchemaMetaPanel() {
            nameField_ = addLineField( "Name" );
            ntableField_ = addLineField( "Tables" );
            descripField_ = addMultiLineField( "Description" );
        }

        /**
         * Configures this component to display metadata for a given schema.
         *
         * @param  schema  schema metadata to display
         */
        public void setSchema( SchemaMeta schema ) {
            if ( schema != schema_ ) {
                schema_ = schema;
                setFieldText( nameField_,
                              schema == null ? null : schema.getName() );
                setFieldText( descripField_,
                              schema == null ? null : schema.getDescription() );
                TableMeta[] tables = schema == null ? null : schema.getTables();
                setFieldText( ntableField_, arrayLength( tables ) );
            }
        }
    }

    /**
     * MetaPanel subclass for displaying service metadata.
     */
    private static class ResourceMetaPanel extends MetaPanel {
        private final JTextComponent ivoidField_;
        private final JTextComponent servurlField_;
        private final JTextComponent nameField_;
        private final JTextComponent titleField_;
        private final JTextComponent refurlField_;
        private final JTextComponent examplesurlField_;
        private final JTextComponent sizeField_;
        private final JTextComponent publisherField_;
        private final JTextComponent creatorField_;
        private final JTextComponent contactField_;
        private final JTextComponent descripField_;
        private final JTextComponent dmField_;
        private final JTextComponent geoField_;
        private final JTextComponent udfField_;

        /**
         * Constructor.
         *
         * @param  urlHandler  handles URLs that the user clicks on; may be null
         */
        ResourceMetaPanel( UrlHandler urlHandler ) {
            nameField_ = addLineField( "Short Name" );
            titleField_ = addLineField( "Title" );
            ivoidField_ = addLineField( "IVO ID" );
            servurlField_ = addLineField( "Service URL" );
            refurlField_ = addUrlField( "Reference URL", urlHandler );
            examplesurlField_ = addUrlField( "Examples URL", urlHandler );
            sizeField_ = addLineField( "Size" );
            publisherField_ = addMultiLineField( "Publisher" );
            creatorField_ = addMultiLineField( "Creator" );
            contactField_ = addMultiLineField( "Contact" );
            descripField_ = addMultiLineField( "Description" );
            dmField_ = addMultiLineField( "Data Models" );
            geoField_ = addMultiLineField( "Geometry Functions" );
            udfField_ = addHtmlField( "User-Defined Functions" );
        }

        /**
         * Sets basic identity information for this service.
         *
         * @param  serviceUrl   TAP service URL, may be null
         * @param  ivoid  ivorn for TAP service registry resource, may be null
         */
        public void setId( URL serviceUrl, String ivoid ) {
            setFieldText( servurlField_,
                          serviceUrl == null ? null : serviceUrl.toString() );
            setFieldText( ivoidField_, ivoid );
        }

        /**
         * Supplies a string indicating the size of the service.
         *
         * @param   sizeTxt  text, for instance count of schemas and tables
         */
        public void setSize( String sizeTxt ) {
            setFieldText( sizeField_, sizeTxt );
        }

        /**
         * Sets resource information.
         * The argument is a map of standard RegTAP resource column names
         * to their values.
         *
         * @param  map  map of service resource metadata items,
         *              may be empty but not null
         */
        public void setResourceInfo( Map<String,String> info ) {
            setFieldText( nameField_, info.remove( "short_name" ) );
            setFieldText( titleField_, info.remove( "res_title" ) );
            setFieldText( refurlField_, info.remove( "reference_url" ) );
            setFieldText( descripField_, info.remove( "res_description" ) );
        }

        /**
         * Sets role information.
         *
         * @param  roles  list of known roles, may be empty but not null
         */
        public void setResourceRoles( RegRole[] roles ) {
            setFieldText( publisherField_, getRoleText( roles, "publisher" ) );
            setFieldText( creatorField_, getRoleText( roles, "creator" ) );
            setFieldText( contactField_, getRoleText( roles, "contact" ) );
            URL logoUrl = getLogoUrl( roles );
            setLogo( logoUrl == null ? null : new ImageIcon( logoUrl ) );
        }

        /**
         * Sets the examples URL to display.
         *
         * @param  examples URL, or null
         */
        public void setExamplesUrl( String examplesUrl ) {
            setFieldText( examplesurlField_, examplesUrl );
        }

        /**
         * Sets capability information to display.
         *
         * @param   tcap  capability object, may be null
         */
        public void setCapability( TapCapability tcap ) {
            setFieldText( dmField_, getDataModelText( tcap ) );
            setFieldText( geoField_, getGeoFuncText( tcap ) );
            setFieldText( udfField_, getUdfHtml( tcap ) );
        }

        /**
         * Returns a text string displaying data model information for
         * the given capability.
         *
         * @param   tcap  capability object, may be null
         * @return   text summarising data model information, or null
         */
        private static String getDataModelText( TapCapability tcap ) {
            if ( tcap == null ) {
                return null;
            }
            String[] dms = tcap.getDataModels();
            if ( dms == null || dms.length == 0 ) {
                return null;
            }
            StringBuffer sbuf = new StringBuffer();
            if ( dms != null ) {
                for ( String dm : dms ) {
                    if ( sbuf.length() != 0 ) {
                        sbuf.append( '\n' );
                    }
                    sbuf.append( dm );
                }
            }
            return sbuf.toString();
        }

        /**
         * Returns a text string displaying ADQL geometry function
         * information for the given capability.
         *
         * @param   tcap  capability object, may be null
         * @return   text summarising available geometry functions, or null
         */
        private static String getGeoFuncText( TapCapability tcap ) {
            if ( tcap == null ) {
                return null;
            }
            TapLanguage[] langs = getAdqlLanguages( tcap );
            if ( langs.length == 0 ) {
                return null;
            }
            else if ( langs.length == 1 ) {
                return getGeoFuncText( langs[ 0 ] );
            }
            else {

                /* There won't usually be multiple ADQL-like languages.
                 * Cope with the case where there are, but the result
                 * may be ugly. */
                StringBuffer sbuf = new StringBuffer();
                for ( TapLanguage lang : langs ) {
                    String geoTxt = getGeoFuncText( lang );
                    if ( geoTxt != null && geoTxt.length() > 0 ) {
                        if ( sbuf.length() != 0 ) {
                            sbuf.append( '\n' );
                        }
                        sbuf.append( lang.getName() )
                            .append( '\n' )
                            .append( "--------\n" )
                            .append( geoTxt );
                    }
                }
                return sbuf.toString();
            }
        }

        /**
         * Returns a text string displaying information about a RegRole
         * category.
         *
         * @param  roles  list of all known role entities
         * @param  baseRole   role category
         * @return  text, may be multi-line
         */
        private static String getRoleText( RegRole[] roles, String baseRole ) {
            StringBuffer sbuf = new StringBuffer();
            for ( RegRole role : roles ) {
                String name = role.getName();
                String email = role.getEmail();
                boolean hasName = name != null && name.trim().length() > 0;
                boolean hasEmail = email != null && email.trim().length() > 0;
                if ( baseRole.equalsIgnoreCase( role.getBaseRole() )
                     && ( hasName || hasEmail ) ) {
                    if ( sbuf.length() > 0 ) {
                        sbuf.append( '\n' );
                    }
                    if ( hasName ) {
                        sbuf.append( name.trim() );
                    }
                    if ( hasName && hasEmail ) {
                        sbuf.append( ' ' );
                    }
                    if ( hasEmail ) {
                        sbuf.append( '<' )
                            .append( email.trim() )
                            .append( '>' );
                    }
                }
            }
            return sbuf.toString();
        }

        /**
         * Returns the URL of a logo icon associated with a set of roles.
         *
         * @param  roles  registry roles
         * @return   logo image URL, or null
         */
        private static URL getLogoUrl( RegRole[] roles ) {
            for ( RegRole role : roles ) {
                String logo = role.getLogo();
                if ( logo != null && logo.trim().length() > 0 ) {
                    try {
                        return new URL( logo );
                    }
                    catch ( MalformedURLException e ) {
                    }
                }
            }
            return null;
        }

        /**
         * Returns HTML formatted text displaying information about ADQL UDFs
         * for the given capability.
         *
         * @param   tcap  capability object, may be null
         * @return   HTML summarising available geometry functions, or null
         */
        private static String getUdfHtml( TapCapability tcap ) {
            if ( tcap == null ) {
                return null;
            }
            TapLanguage[] langs = getAdqlLanguages( tcap );
            if ( langs.length == 0 ) {
                return null;
            }
            else if ( langs.length == 1 ) {
                return getUdfHtml( langs[ 0 ] );
            }
            else {

                /* There won't usually be multiple ADQL-like languages,
                 * Cope with the case where there are, but the result
                 * may be ugly. */
                StringBuffer sbuf = new StringBuffer();
                for ( TapLanguage lang : langs ) {
                    String lname = lang.getName();
                    String udfHtml = getUdfHtml( lang );
                    if ( udfHtml != null && udfHtml.length() > 0 ) {
                        sbuf.append( "<dt>" )
                            .append( escapeHtml( lname == null ? "??"
                                                               : lname ) )
                            .append( "</dt>\n" )
                            .append( "<dd>" )
                            .append( udfHtml )
                            .append( "</dd>\n" );
                    }
                }
                return sbuf.length() > 0
                     ? "<dl>\n" + sbuf + "</dl>"
                     : null;
            }
        }

        /**
         * Returns a text string displaying ADQL geometry function
         * information for the given language.
         *
         * @param  lang  TAP language object
         * @return  text summarising available geometry functions, or null
         */
        private static String getGeoFuncText( TapLanguage lang ) {
            TapLanguageFeature[] geoFeats =
                lang.getFeaturesMap()
                    .get( TapCapability.ADQLGEO_FEATURE_TYPE );
            if ( geoFeats == null || geoFeats.length == 0 ) {
                return null;
            }
            List<String> geoFuncs = new ArrayList<String>();
            for ( TapLanguageFeature feat : geoFeats ) {
                geoFuncs.add( feat.getForm() );
            }
            Collections.sort( geoFuncs );
            StringBuffer sbuf = new StringBuffer(); 
            for ( String func : geoFuncs ) {
                if ( sbuf.length() != 0 ) {
                    sbuf.append( ", " );
                }
                sbuf.append( func );
            }
            return sbuf.toString();
        }

        /**
         * Returns HTML formatted text displaying information about ADQL UDFs
         * for the given language.
         *
         * @param  lang  TAP language object
         * @return   HTML summarising available geometry functions, or null
         */
        private static String getUdfHtml( TapLanguage lang ) {
            TapLanguageFeature[] udfFeats =
                lang.getFeaturesMap()
                    .get( TapCapability.UDF_FEATURE_TYPE );
            if ( udfFeats == null || udfFeats.length == 0 ) {
                return null;
            }
            StringBuffer sbuf = new StringBuffer();
            for ( TapLanguageFeature feat : udfFeats ) {
                String form = feat.getForm();
                String descrip = feat.getDescription();
                sbuf.append( "<dt>" )
                    .append( "<strong><code>" )
                    .append( escapeHtml( form == null ? "??" : form ) )
                    .append( "</code></strong>" )
                    .append( "</dt>\n" );
                if ( descrip != null ) {
                    sbuf.append( "<dd>" )
                        .append( escapeHtml( descrip ) )
                        .append( "</dd>\n" );
                }
            }
            return sbuf.length() > 0 ? "<dl>\n" + sbuf + "</dl>"
                                     : null;
        }

        /**
         * Returns a list of the ADQL-like languages available from a
         * given TAP Capability object.
         *
         * @param  tcap  capability
         * @return   list of ADQL-like language objects, not null
         */
        private static TapLanguage[] getAdqlLanguages( TapCapability tcap ) {
            List<TapLanguage> adqlList = new ArrayList<TapLanguage>();
            if ( tcap != null ) {
                TapLanguage[] langs = tcap.getLanguages();
                if ( langs != null ) {
                    for ( TapLanguage lang : langs ) {
                        if ( "adql".equalsIgnoreCase( lang.getName() ) ) {
                            adqlList.add( lang );
                        }
                    }
                }
            }
            return adqlList.toArray( new TapLanguage[ 0 ] );
        }

        /**
         * Makes plain text safe for interpolation into HTML source.
         *
         * @param  txt  raw text
         * @return  escaped text
         */
        private static String escapeHtml( String txt ) {
            return txt.replace( "&", "&amp;" )
                      .replace( "<", "&lt;" )
                      .replace( ">", "&gt;" );
        }
    }
}
