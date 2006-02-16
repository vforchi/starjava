/*
 * Copyright (C) 2005 Central Laboratory of the Research Councils
 *
 *  History:
 *     17-MAY-2005 (Peter W. Draper):
 *       Original version.
 */
package uk.ac.starlink.splat.iface;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.File;
import java.io.FileWriter;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Vector;
import java.util.prefs.Preferences;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ComboBoxModel;
import javax.swing.DefaultComboBoxModel;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import uk.ac.starlink.ast.AstException;
import uk.ac.starlink.ast.Frame;
import uk.ac.starlink.ast.FrameSet;
import uk.ac.starlink.ast.Mapping;
import uk.ac.starlink.ast.SpecFrame;
import uk.ac.starlink.ast.WinMap;
import uk.ac.starlink.ast.gui.DecimalField;
import uk.ac.starlink.ast.gui.ScientificFormat;
import uk.ac.starlink.ast.gui.ScientificSpinner;
import uk.ac.starlink.splat.ast.ASTJ;
import uk.ac.starlink.splat.data.EditableSpecData;
import uk.ac.starlink.splat.data.LineIDSpecData;
import uk.ac.starlink.splat.data.SpecData;
import uk.ac.starlink.splat.data.SpecDataFactory;
import uk.ac.starlink.splat.iface.images.ImageHolder;
import uk.ac.starlink.splat.plot.PlotControl;
import uk.ac.starlink.splat.util.MathUtils;
import uk.ac.starlink.splat.util.SplatException;
import uk.ac.starlink.splat.util.Utilities;
import uk.ac.starlink.util.PhysicalConstants;
import uk.ac.starlink.util.gui.BasicFileChooser;
import uk.ac.starlink.util.gui.BasicFileFilter;
import uk.ac.starlink.util.gui.GridBagLayouter;

/**
 * Provides a toolbox for optionally flipping and translating a modifiable
 * spectrum. Flipping allows the detailed line shape to be compared.
 *
 * @author Peter W. Draper
 * @version $Id$
 */
public class FlipFrame
    extends JFrame
    implements ActionListener, ChangeListener, PlotListener, ItemListener,
               LineProvider
{
    /** UI preferences. */
    protected static Preferences prefs =
        Preferences.userNodeForPackage( FlipFrame.class );

    /** List of spectra and properties that we have modified. */
    protected Map storedPropertiesMap = new HashMap();

    /** JComboBox that displays the list of available spectra. */
    protected JComboBox availableSpectra = new JComboBox();

    /** Flip state. */
    protected JCheckBox flipBox = null;

    /** Is offset to be interpreted as a redshift? */
    protected JCheckBox redshiftBox = null;

    /** User offset control. */
    protected ScientificSpinner offsetSpinner = null;

    /** And the model. */
    protected SpinnerNumberModel offsetModel = null;

    /** Spinner increment. */
    protected DecimalField incrementSpinner = null;

    /** Flip coordinate. */
    protected DecimalField flipCentre = null;

    /** The global list of spectra and plots. */
    protected GlobalSpecPlotList globalList = GlobalSpecPlotList.getInstance();

    /** Content pane of frame. */
    protected JPanel contentPane = null;

    /** The PlotControl that is displaying the current spectrum. */
    protected PlotControl plot = null;

    /** Menu item retaining state of SPEFO changes */
    protected JCheckBoxMenuItem spefoBox = null;

    /** Simple text area for SPEFO values. */
    protected JTextArea spefoArea = null;
    protected JTextArea spefoNotes = null;

    /** LineVisitor for stepping between lines */
    LineVisitor visitor = null;

    /** Menu item retaining state of visitor state changes */
    protected JCheckBoxMenuItem singleComparisonBox = null;

    /** File chooser used for line visitor files */
    protected BasicFileChooser fileChooser = null;

    /**
     * Create an instance.
     */
    public FlipFrame( PlotControl plot )
    {
        contentPane = (JPanel) getContentPane();
        contentPane.setLayout( new BorderLayout() );
        initUI();
        initFrame();
        setPlot( plot );
    }

    /**
     * Get the PlotControl that we are using.
     *
     * @return the PlotControl
     */
    public PlotControl getPlot()
    {
        return plot;
    }

    /**
     * Set the PlotControlFrame that has the spectrum that we are to
     * process.
     *
     * @param plot the PlotControl reference.
     */
    public void setPlot( PlotControl plot )
    {
        this.plot = plot;

        //  We are listening to changes to the available spectra.
        globalList.addPlotListener( this );
        updateNames();
    }

    /**
     * Initialise the main part of the user interface.
     */
    protected void initUI()
    {
        //  Panel for top of contentPane.
        JPanel topPanel = new JPanel( new BorderLayout() );
        contentPane.add( topPanel, BorderLayout.NORTH );
        GridBagLayouter gbl0 = new GridBagLayouter( topPanel,
                                                    GridBagLayouter.SCHEME3 );

        //  Panel for spectrum choice, includes copy of current spectrum.
        JPanel spectrumPanel = new JPanel();
        spectrumPanel.setBorder
            ( BorderFactory.createTitledBorder( "Comparison spectrum:" ) );
        GridBagLayouter gbl1 = new GridBagLayouter( spectrumPanel,
                                                    GridBagLayouter.SCHEME3 );

        //  Controls for copying current spectrum
        JLabel copyLabel = new JLabel( "Copy current: " );
        gbl1.add( copyLabel, false );

        //  If current spectrum should be flipped.
        flipBox = new JCheckBox( "Flip" );
        flipBox.setSelected( true );
        flipBox.setToolTipText( "Flip copy left to right" );
        flipBox.addActionListener( new ActionListener() {
                public void actionPerformed( ActionEvent e ) {
                    flipCentre.setEnabled( flipBox.isSelected() );
                }
            });
        gbl1.add( flipBox, false );

        //  Coordinate of flip.
        JLabel flipCentreLabel = new JLabel( "Flip centre:" );
        gbl1.add( flipCentreLabel, false );

        ScientificFormat scientificFormat = new ScientificFormat();
        flipCentre = new DecimalField( 0.0, 5, scientificFormat );
        flipCentre.setToolTipText( "Coordinate of flip centre, " +
                                   "zero for visible centre of plot, " +
                                   "units of current spectrum" );
        gbl1.add( flipCentre, true );

        //  Grab button. This creates the copy, which may be flipped.
        LocalAction copyAction = new LocalAction( LocalAction.COPY,
                                                  "Create copy" );
        JButton copyButton = new JButton( copyAction );
        copyButton.setToolTipText( "Press to create a copy of the current " +
                                   "spectrum in the associated plot" );
        gbl1.add( Box.createGlue(), false );
        gbl1.add( copyButton, false );
        gbl1.add( Box.createGlue(), true );

        //  Add controls for choosing the spectrum.
        JLabel availableSpectraLabel = new JLabel( "Spectrum:" );
        gbl1.add( availableSpectraLabel, false );
        String tipText = "Select a spectrum for translating ";
        availableSpectraLabel.setToolTipText( tipText );

        availableSpectra.setRenderer( new LineRenderer( availableSpectra ) );
        gbl1.add( availableSpectra, true );
        availableSpectra.setToolTipText( tipText );

        //  Respond to changes in selection.
        availableSpectra.addItemListener( this );
        availableSpectra.setSelectedIndex( -1 );

        //  Make the spectrum the visitor list.
        LocalAction makeVisAction =
            new LocalAction( LocalAction.MAKEVIS, "Set as visitor list" );
        JButton makeVisButton = new JButton( makeVisAction );
        makeVisButton.setToolTipText
            ( "Press to make this spectrum the visitor line list");
        gbl1.add( Box.createGlue(), false );
        gbl1.add( makeVisButton, false );
        gbl1.add( Box.createGlue(), true );

        //  Translation controls.
        JPanel transPanel = new JPanel();
        transPanel.setBorder( BorderFactory.createTitledBorder
                              ( "Comparison spectrum translation:" ) );
        GridBagLayouter gbl2 = new GridBagLayouter( transPanel,
                                                    GridBagLayouter.SCHEME3 );

        //  Offset, increment and redshift.
        redshiftBox = new JCheckBox( "Redshift" );
        redshiftBox.setSelected( false );
        redshiftBox.setToolTipText( "Interpret offset as a redshift" );
        gbl2.add( redshiftBox, true );

        JLabel incrLabel = new JLabel( "Increment:" );
        gbl2.add( incrLabel, false );

        scientificFormat = new ScientificFormat();
        incrementSpinner = new DecimalField( 10.0, 5, scientificFormat );
        incrementSpinner.addActionListener( this );
        incrementSpinner.setToolTipText
            ( "Increment used for spinner controls" );
        gbl2.add( incrementSpinner, true );

        JLabel spinnerLabel = new JLabel( "Offset:" );
        gbl2.add( spinnerLabel, false );

        offsetModel = new SpinnerNumberModel( 0.0, -Double.MAX_VALUE,
                                              Double.MAX_VALUE, 10.0 );
        offsetSpinner = new ScientificSpinner( offsetModel );

        offsetSpinner.addChangeListener( this );
        offsetSpinner.setToolTipText
            ( "Offset of spectrum from initial position" );
        gbl2.add( offsetSpinner, true );

        gbl0.add( spectrumPanel, true );
        gbl0.eatLine();
        gbl0.add( transPanel, true );

        //  LineVisitor for stepping through a list.
        visitor = new LineVisitor( this );
        visitor.setBorder(BorderFactory.createTitledBorder("Visitor:"));
        visitor.setEnabled( false );
        visitor.setToolTipText
            ( "Step through a sequence of lines loaded from a file list" );
        gbl0.add( visitor, true );

        //  Add the menuBar.
        JMenuBar menuBar = new JMenuBar();
        setJMenuBar( menuBar );

        //  Get icons for menus and action bar.
        ImageIcon closeImage =
            new ImageIcon( ImageHolder.class.getResource( "close.gif" ) );
        ImageIcon resetImage =
            new ImageIcon( ImageHolder.class.getResource( "reset.gif" ) );

        //  File menu.
        JMenu fileMenu = new JMenu( "File" );
        menuBar.add( fileMenu );

        //  Read in list for LineVisitor control.
        LocalAction readVisitorAction =
            new LocalAction(LocalAction.READVISITOR, "Read visitor line list");
        fileMenu.add( readVisitorAction );

        //  Action bar for buttons.
        JPanel actionBar = new JPanel();

        //  Add an action to reset the spectrum to the default transform.
        LocalAction resetAction = new LocalAction( LocalAction.RESET,
                                                   "Reset",
                                                   resetImage );
        fileMenu.add( resetAction );
        JButton resetButton = new JButton( resetAction );
        actionBar.add( Box.createGlue() );
        actionBar.add( resetButton );
        resetButton.setToolTipText( "Reset spectrum to default offset and"
                                    + " clear visitor states" );

        //  Add an action to close the window.
        LocalAction closeAction = new LocalAction( LocalAction.CLOSE,
                                                   "Close",
                                                   closeImage );
        fileMenu.add( closeAction );
        JButton closeButton = new JButton( closeAction );
        actionBar.add( Box.createGlue() );
        actionBar.add( closeButton );
        closeButton.setToolTipText( "Close window" );

        actionBar.add( Box.createGlue() );
        contentPane.add( actionBar, BorderLayout.SOUTH );

        //  Options menu.
        JMenu optionsMenu = new JMenu( "Options" );
        menuBar.add( optionsMenu );

        //  Do not create new comparison spectra for each visitor.
        singleComparisonBox =
            new JCheckBoxMenuItem( "One spectrum for visitor" );
        optionsMenu.add( singleComparisonBox );
        boolean state = prefs.getBoolean( "FlipFrame_ONECMP", false );
        singleComparisonBox.setSelected( state );
        singleComparisonBox.addActionListener( new ActionListener() {
                public void actionPerformed( ActionEvent e ) {
                    boolean state = singleComparisonBox.isSelected();
                    prefs.putBoolean( "FlipFrame_ONECMP", state );
                }
            });

        //  Switch on the SPEFO additions.
        LocalAction spefoAction = new LocalAction( LocalAction.SPEFO,
                                                   "SPEFO options" );
        spefoBox = new JCheckBoxMenuItem( spefoAction );
        optionsMenu.add( spefoBox );
        state = prefs.getBoolean( "FlipFrame_SPEFO", false );
        spefoBox.setSelected( state );
        makeSpefoChanges();

        //  Add the help menu.
        HelpFrame.createHelpMenu( "flipper-window", "Help on window",
                                  menuBar, null );
    }

    /**
     * Initialise frame properties (disposal, title, menus etc.).
     */
    protected void initFrame()
    {
        setTitle( Utilities.getTitle( "Flip/translate spectrum" ) );
        setDefaultCloseOperation( JFrame.HIDE_ON_CLOSE );
        if ( spefoBox.isSelected() ) {
            setSize( new Dimension( 400, 700 ) );
        }
        else {
            setSize( new Dimension( 400, 500 ) );
        }
        setVisible( true );
    }

    /**
     * Make a copy of the current spectrum and apply a flip, if requested. The
     * initial translation is set so that the feature in the middle of the
     * Plot is shown in the middle.
     */
    protected void copyFlipCurrentSpectrum()
    {
        boolean flipped = flipBox.isSelected();

        SpecData spec = plot.getCurrentSpectrum();
        String name = null;
        if ( flipped ) {
            name = "Flip of: " + spec.getShortName();
        }
        else {
            name = "Copy of: " + spec.getShortName();
        }
        EditableSpecData comparisonSpectrum = null;
        try {
            comparisonSpectrum =
                SpecDataFactory.getInstance().createEditable( name, spec,
                                                              false );
            globalList.add( comparisonSpectrum );
        }
        catch (Exception e) {
            JOptionPane.showMessageDialog( this, e.getMessage(),
                                           "Failed to create copy",
                                           JOptionPane.ERROR_MESSAGE );
            return;
        }

        //  Decide how to flip and transform the spectrum. If not flipping no
        //  need to offset, otherwise reflection about zero needs an offset to
        //  get back to same part of display.
        double offset = 0.0;
        double scale = 1.0;
        double centre = 0.0;
        if ( flipped ) {
            scale = -1.0;
            centre = flipCentre.getDoubleValue();
            if ( centre == 0.0 ) {
                //  Use centre of visible plot.
                double[] view = plot.getViewCoordinates();
                centre = view[0] + 0.5 * ( view[2] - view[0] );
            }
            offset = 2.0 * centre;
        }
        flipTransform( scale, offset, comparisonSpectrum, false );

        //  Add this to our plot and make it the selected spectrum.
        try {
            globalList.addSpectrum( plot, comparisonSpectrum );
            if ( flipped ) {
                //  Reposition plot window, may have zoomed on feature.
                plot.centreOnXCoordinate( Double.toString( centre ) );
            }
            addSpectrum( comparisonSpectrum, true );

            //  Do this last so that the shift to 0.0 works on the final
            //  version of comparison spectrum that is cached.
            availableSpectra.setSelectedItem( comparisonSpectrum );
        }
        catch (SplatException e) {
            JOptionPane.showMessageDialog( this, e.getMessage(),
                                           "Failed to add spectrum to plot",
                                           JOptionPane.ERROR_MESSAGE );
        }
    }

    /**
     * Apply a scale and offset transformation to a given spectrum, or to the
     * current spectrum if the given spectrum is null. If redshift is true
     * then the offset is interpreted as a redshift to be applied (and the
     * scale is ignored).
     */
    private void flipTransform( double scale, double offset,
                                EditableSpecData spectrum, boolean redshift  )
    {
        //  If no spectrum has been given then we need to access the currently
        //  selected one.
        if ( spectrum == null ) {
            spectrum = getComparisonSpectrum();
        }

        //  Recover the stored properties, such as the original FrameSet
        //  (need this to avoid adding redundant frames and mappings) and
        //  Frame (defines coordinate system).
        StoredProperties storedProperties = getStoredProperties( spectrum );
        FrameSet frameSet = storedProperties.getFrameSet();
        Frame frame = storedProperties.getFrame();

        //  Store the offset so we can set the offset widget to the right
        //  value for this spectrum next time it is selected.
        storedProperties.setOffset( offset );

        //  Create a WinMap for the linear transformation.
        double[] ina = new double[1];
        double[] inb = new double[1];
        double[] outa = new double[1];
        double[] outb = new double[1];
        boolean simple = true;
        if ( redshift ) {
            //  Pick out first axis from FrameSet, this should be a SpecFrame,
            //  otherwise we don't care about units, systems etc. and will
            //  assume everything just works as a wavelength.
            int iaxes[] = { 1 };
            Frame picked = frameSet.pickAxes( 1, iaxes, null );
            if ( picked instanceof SpecFrame ) {
                boolean islineid = ( spectrum instanceof LineIDSpecData );
                if ( redshiftSpecFrameSet( frameSet, islineid, offset ) ) {
                    simple = false;
                }
                else {
                    //  Failed.
                    return;
                }
            }
            else {
                //  Redshift is offset value so times wavelength by (z+1).
                ina[0] = 0.0;
                inb[0] = 1.0;
                outa[0] = ina[0] * ( offset + 1 );
                outb[0] = inb[0] * ( offset + 1 );
            }
        }
        else {
            ina[0] = 0.0;
            inb[0] = 1.0;
            outa[0] = ina[0] * scale + offset;
            outb[0] = inb[0] * scale + offset;
        }
        if ( simple ) {
            WinMap winMap = new WinMap( 1, ina, inb, outa, outb );

            //  Add mapping and original Frame to FrameSet.
            frameSet.addFrame( FrameSet.AST__CURRENT, winMap, frame );
        }

        //  Make changes propagate.
        try {
            spectrum.setFrameSet( frameSet );
            globalList.notifySpecListenersModified( spectrum );
        }
        catch (Exception e) {
            JOptionPane.showMessageDialog( this, e.getMessage(),
                        "Failed to update spectral coordinates",
                        JOptionPane.ERROR_MESSAGE );
        }
    }

    /**
     * Transform the spectral coordinates of a FrameSet by a redshift factor.
     *
     * To deal with all occasions (nearly) we must let AST work this out. The
     * way to do this is by setting the observer reference frame to be the
     * Source and add a source velocity equivalent to the redshift. We then
     * move the reference frame back to some solar system frame...
     * The problems with this approach are when the reference frame is
     * already at the Source... In that case I think we would need to
     * construct a mapping for all possible spectral coordinate systems and
     * units, a non-trivial task. Assume a fix for line identifiers, which we
     * know are at "source.
     */
    protected boolean redshiftSpecFrameSet( FrameSet frameSet,
                                            boolean islineid,
                                            double redshift )
    {
        Frame current = frameSet.getFrame( FrameSet.AST__CURRENT );

        String stdOfRest = current.getC( "StdOfRest" );
        boolean stdOfRestFudged = false;
        if ( stdOfRest.equalsIgnoreCase( "Source" ) ) {
            //  We're stuffed, unless this is a line identifier.
            if ( islineid ) {
                //  Line identifier.
                stdOfRestFudged = true;
                stdOfRest = "Heliocentric";
            }
            else {
                JOptionPane.showMessageDialog
                    ( this, "Cannot redshift when the" +
                      " spectral standard of rest is already set to 'Source'",
                      "Cannot redshift", JOptionPane.ERROR_MESSAGE );
                return false;
            }
        }

        //  Change standard of rest to "Source" and set the velocity, without
        //  causing a remap to the FrameSet.
        double initialVelocity = current.getD( "SourceVel" );
        current.setC( "StdOfRest", "Source" );
        double redshiftVelocity =
            0.001 * MathUtils.redshiftToVelocity( redshift ); // Km/s
        current.setD( "SourceVel", redshiftVelocity );

        //  Now apply the redshift by moving the frameSet back to the original
        //  reference frame, this should cause a remapping.
        frameSet.setC( "StdOfRest", stdOfRest );

        //  Restore any original source velocity, without causing a remap.
        current.setD( "SourceVel", initialVelocity );

        //  If line identifier, we were always at "Source".
        if ( stdOfRestFudged ) {
            frameSet.setC( "StdOfRest", "Source" );
        }

        return true;
    }

    /**
     * Update the spectra available for translation. Only EditableSpecData
     * instances are allowed and only those in the plot.
     */
    protected void updateNames()
    {
        //  Keep reference to currently selected spectrum.
        EditableSpecData currentSpectrum = getComparisonSpectrum();
        if ( globalList.getSpectrumIndex( currentSpectrum ) == -1 ) {
            //  This has been removed.
            currentSpectrum = null;
        }

        //  Re-create a vector/model for this. Faster as avoids
        //  listener updates also gets resize right.
        Vector v = new Vector();
        SpecData spec = null;
        for ( int i = 0; i < globalList.specCount(); i++ ) {
            spec = globalList.getSpectrum( i );
            if ( spec instanceof EditableSpecData ) {
                if ( plot.isDisplayed( spec ) ) {
                    v.add( spec );
                    if ( currentSpectrum == null ) {
                        //  Top of list becomes current.
                        currentSpectrum = (EditableSpecData) spec;
                    }
                }
            }
        }
        ComboBoxModel model = new DefaultComboBoxModel( v );
        availableSpectra.setModel( model );

        //  Restore selected spectrum.
        if ( currentSpectrum != null && globalList.specCount() > 1 ) {
            model.setSelectedItem( currentSpectrum );
        }
    }

    /**
     * Access the comparison spectrum, this is the one selected in the
     * JComboBox of spectra.
     */
    protected EditableSpecData getComparisonSpectrum()
    {
        EditableSpecData spectrum =
            (EditableSpecData) availableSpectra.getSelectedItem();
        if ( spectrum == null ) {
            //  No selected spectra, look for any on the changed list.
            if ( storedPropertiesMap.size() > 0 ) {
                Iterator i = storedPropertiesMap.keySet().iterator();
                spectrum = (EditableSpecData) i.next();
            }
        }
        return spectrum;
    }

    /**
     * Get the StoredProperties instance for a spectrum.
     */
    protected StoredProperties getStoredProperties( EditableSpecData spectrum )
    {
        if ( ! storedPropertiesMap.containsKey( spectrum ) ) {
            //  Unknown spectrum, so add it.
            addSpectrum( spectrum, false );
        }
        return (StoredProperties) storedPropertiesMap.get( spectrum );
    }

    /**
     * Apply the current offset to the selected spectrum.
     */
    protected void applyOffset()
    {
        double offset = ((Double)offsetSpinner.getValue()).doubleValue();
        flipTransform( 1.0, offset, null, redshiftBox.isSelected() );

        if ( spefoBox.isSelected() ) {
            spefoArea.selectAll();
            spefoArea.cut();

            // Display the SPEFO values. For flipped spectrum the
            // offset is halved and we'd like an RV measurement. Note this
            // assumes coordinates are wavelength...
            offset *= 0.5;
            spefoArea.append( "  Corrected offset = " + offset + "\n" );
            double centre = flipCentre.getDoubleValue();
            if ( centre != 0.0 ) {
                double rv = PhysicalConstants.SPEED_OF_LIGHT * offset / centre;
                spefoArea.append( "  RV = " + rv + "\n" );
            }
        }
    }

    /**
     * Add a spectrum to the changed list. Replaces an existing spectrum, if
     * replace is true.
     */
    protected void addSpectrum( EditableSpecData spectrum, boolean replace )
    {
        if ( storedPropertiesMap.containsKey( spectrum ) ) {
            if ( replace ) {
                storedPropertiesMap.put( spectrum,
                                         new StoredProperties( spectrum ) );
            }
        }
        else if ( spectrum != null ) {
            storedPropertiesMap.put( spectrum,
                                     new StoredProperties( spectrum ) );
        }
    }

    /**
     * Reset selected spectrum to it default offset.
     */
    protected void resetSelectedSpectrum()
    {
        offsetSpinner.setValue( new Double( 0.0 ) );
    }

    /**
     * Read a line list of positions and initialise the LineVisitor with
     * them.
     */
    protected void readVisitorLineList()
    {
        if ( visitor != null ) {
            initFileChooser();
            int result = fileChooser.showOpenDialog( this );
            if ( result == fileChooser.APPROVE_OPTION ) {
                File file = fileChooser.getSelectedFile();
                try {
                    SpecData specData = visitor.readLines( file );
                    if ( specData != null ) {
                        visitor.setEnabled( true );
                        globalList.add( specData );
                        globalList.addSpectrum( plot, specData );
                    }
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Initialise the file chooser to have the necessary filters.
     */
    protected void initFileChooser()
    {
        if ( fileChooser == null ) {
            fileChooser = new BasicFileChooser( false );
            fileChooser.setMultiSelectionEnabled( false );

            //  Add a filter for text files and line identifiers.
            BasicFileFilter textFileFilter =
                new BasicFileFilter( "txt", "TEXT files" );
            fileChooser.addChoosableFileFilter( textFileFilter );
            BasicFileFilter idsFileFilter =
                new BasicFileFilter( "ids", "Line identification files" );
            fileChooser.addChoosableFileFilter( idsFileFilter );

            //  But allow all files as well.
            fileChooser.addChoosableFileFilter
                ( fileChooser.getAcceptAllFileFilter() );
        }
    }

    /**
     * Set the comparison spectrum as the visitor line list.
     */
    protected void setVisitorLineList()
    {
        if ( visitor != null && getComparisonSpectrum() != null ) {
            visitor.setLineList( getComparisonSpectrum() );
            visitor.setEnabled( true );
        }
    }

    /**
     * Make SPEFO-like changes to interface ala Petr Skoda.
     */
    protected void makeSpefoChanges()
    {
        boolean display = spefoBox.isSelected();
        prefs.putBoolean( "FlipFrame_SPEFO", display );
        if ( display && spefoArea == null ) {
            JPanel spefoPanel = new JPanel();
            spefoPanel.setBorder(BorderFactory.createTitledBorder("SPEFO:"));
            GridBagLayouter gbl =
                new GridBagLayouter( spefoPanel, GridBagLayouter.SCHEME4 );

            //  Need display that shows additional values.
            spefoArea = new JTextArea( 2, 30 );
            spefoArea.setBorder(BorderFactory.createTitledBorder("Values:"));
            spefoArea.setEditable( false );
            JScrollPane scrollPane = new JScrollPane( spefoArea );
            gbl.add( scrollPane, true );

            //  An area for notes.
            spefoNotes = new JTextArea( 4, 30 );
            spefoNotes.setBorder(BorderFactory.createTitledBorder("Notes:"));
            spefoArea.setEditable( true );
            scrollPane = new JScrollPane( spefoNotes );
            gbl.add( scrollPane, true );

            //  Button for saving to the log file.
            LocalAction spefoSaveAction =
                new LocalAction( LocalAction.SPEFOSAVE,
                                 "Save to SPEFO.log file" );
            JButton spefoSave = new JButton( spefoSaveAction );
            gbl.add( spefoSave, false );
            gbl.eatLine();

            contentPane.add( spefoPanel, BorderLayout.CENTER );
        }
    }

    /**
     * Append the contents of the SPEFO areas to the file SPEFO.log.
     */
    protected void spefoSave()
    {
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter( new FileWriter( "SPEFO.log", true ) );
            SpecData spec = plot.getCurrentSpectrum();

            writer.write( "Spectrum: " + spec.getShortName() + "\n" );
            writer.write( "  Centre = " + flipCentre.getDoubleValue() + "\n" );
            spefoArea.write( writer );
            writer.write( "\n" );
            writer.write( "  Notes: " + "\n" );
            spefoNotes.write( writer );
            writer.write( "\n" );
            writer.close();
        }
        catch (IOException e) {
            JOptionPane.showMessageDialog( this, e.getMessage(),
                                           "Failed writing SPEFO log",
                                           JOptionPane.ERROR_MESSAGE );
        }
    }

    /**
     * Close the window. Delete any ranges that are shown.
     */
    protected void closeWindowEvent()
    {
        globalList.removePlotListener( this );
        this.dispose();
    }

    //
    // Implement ChangeListener so that we update translation.
    //
    public void stateChanged( ChangeEvent e )
    {
        applyOffset();
    }

    //
    // Implement the PlotListener interface so we can keep the list of
    // available spectra up to date.
    //

    public void plotCreated( PlotChangedEvent e )
    {
        //  Do nothing.
    }
    public void plotRemoved( PlotChangedEvent e )
    {
        // Do nothing.
    }

    //
    // Sent when a plot property is changed, assume this means that a
    // spectrum has been added or removed, so update the list.
    //
    public void plotChanged( PlotChangedEvent e )
    {
        updateNames();
        if ( e.getType() == PlotChangedEvent.REMOVED ) {
            //  Spectrum may need removing from our lists.
            SpecData spectrum = globalList.getSpectrum( e.getIndex() );
            storedPropertiesMap.remove( spectrum );
        }

        //  XXX there is a problem here. When spectra are removed from the
        //  GlobalSpecPlotList and are displayed in our Plot, we don't see the
        //  event. Clearly the GlobalSpecPlotList needs to inform all
        //  PlotListeners, but doesn't. May have some other consequences...
    }

    //
    // ItemListener for changes to the selected spectrum.
    //
    public void itemStateChanged( ItemEvent e )
    {
        EditableSpecData comparisonSpectrum = getComparisonSpectrum();
        if ( comparisonSpectrum != null ) {
            StoredProperties storedProperties = 
                getStoredProperties( comparisonSpectrum );

            // Need to restore the offset last used for this spectrum.
            offsetSpinner.setValue
                ( new Double( storedProperties.getOffset() ) );
        }
    }

    //
    // ActionListener for changes to spinner increment.
    //
    public void actionPerformed( ActionEvent e )
    {
        offsetModel.setStepSize
            ( new Double( incrementSpinner.getDoubleValue() ) );
    }

    //
    // LineProvider interface. Used to configure for a visit to a place where
    // a line is expected.
    //
    public void viewSpectrum( SpecData specData )
    {
        //  Make our associated plot display this spectrum.
        SpecData currentSpec = plot.getCurrentSpectrum();
        try {
            plot.removeSpectrum( currentSpec );
            plot.addSpectrum( specData );
            plot.setCurrentSpectrum( specData );
        }
        catch (SplatException e) {
            e.printStackTrace();
            //  And live with it.
        }
    }

    public void viewLine( double coord, Frame coordFrame, Object state )
    {
        //  Move to line and view it and restore any associated state.

        //  Remove comparisonSpectrum, if we're replacing it.
        boolean onespec = singleComparisonBox.isSelected();
        if ( getComparisonSpectrum() != null && ! onespec ) {
            globalList.removeSpectrum( plot, getComparisonSpectrum() );
        }

        //  Attempt to transform coord from its system into the system of the
        //  plot current spectrum.
        SpecData spec = plot.getCurrentSpectrum();
        Frame specFrame =
            spec.getAst().getRef().pickAxes( 1, new int[]{1}, null );
        Mapping mapping = coordFrame.convert( specFrame, "" );
        if ( mapping != null ) {
            double[] frameCoords = new double[1];
            frameCoords[0] = coord;
            double[] tranCoords = mapping.tran1( 1, frameCoords, true );
            coord = tranCoords[0];
        }

        //  Flip centre shows the coordinate, always (note disabled when not
        //  flipping so need extra effort).
        flipCentre.setEnabled( true );
        flipCentre.setDoubleValue( coord );
        flipCentre.setEnabled( flipBox.isSelected() );

        //  Restore old state, unless just have one comparison spectrum. That
        //  retains same values all the time.
        if ( state != null && ! onespec ) {
            // Restoring old state.
            StateStore stateStore = (StateStore) state;
            try {
                //  Comparison spectrum.
                EditableSpecData comparisonSpectrum = 
                    stateStore.getComparisonSpectrum();
                if ( comparisonSpectrum != null ) {
                    globalList.addSpectrum( plot, comparisonSpectrum );
                }

                //  Flip selector.
                flipBox.setSelected( stateStore.isCopyFlip() );

                //  Make sure comparisonSpectrum is current (so changes effect
                //  it).
                if ( comparisonSpectrum != null ) {
                   availableSpectra.setSelectedItem( comparisonSpectrum );
                }

                //  Redshift selector.
                redshiftBox.setSelected( stateStore.isShiftRedShift() );

                //  Increment.
                incrementSpinner.setDoubleValue( stateStore.getIncrement() );

                //  Offset.
                offsetSpinner.setValue( stateStore.getOffset() );

                if ( spefoBox.isSelected() ) {
                    //  SPEFO values:
                    spefoArea.setText( stateStore.getSPEFOValueText() );

                    //  SPEFO Notes:
                    spefoNotes.setText( stateStore.getSPEFONoteText() );
                }
            }
            catch (SplatException e) {
                //  Nothing to do? Could make a dialog report.
                e.printStackTrace();
            }
        }
        else {
            //  Create a new spectrum, or we'll just move to the given
            //  coordinates.
            if ( getComparisonSpectrum() == null || ! onespec ) {
                copyFlipCurrentSpectrum();
            }
        }

        //  Make sure we can view the line.
        plot.centreOnXCoordinate( Double.toString( coord ) );
    }

    public Object getLineState()
    {
        //  Return the current state, for the current line. Not used if a
        //  single comparison spectrum is requested.
        if( singleComparisonBox.isSelected() ) {
            return null;
        }
        return new StateStore();
    }

    //
    // Internal class for storing original properties of any spectra that we
    // change.
    //
    protected class StoredProperties
    {
        private FrameSet originalFrameSet = null;
        private Frame originalFrame = null;
        private double offset = 0.0;

        public StoredProperties( EditableSpecData spectrum )
        {
            originalFrameSet = (FrameSet) spectrum.getFrameSet().copy();
            originalFrame = (Frame)
                originalFrameSet.getFrame(FrameSet.AST__CURRENT).copy();
        }
        public FrameSet getFrameSet()
        {
            return (FrameSet) originalFrameSet.copy();
        }
        public Frame getFrame()
        {
            return (Frame) originalFrame.copy();
        }

        public void setOffset( double offset )
        {
            this.offset = offset;
        }
        public double getOffset()
        {
            return offset;
        }
    }

    //
    // Internal class for storing current state of interface.
    //
    protected class StateStore
    {
        private EditableSpecData spectrum = null;
        private boolean copyFlip = false;
        private boolean shiftRedShift = false;
        private double increment = 0.0;
        private Double offset = null;
        private String spefoValueText = null;
        private String spefoNoteText = null;

        public StateStore()
        {
            setComparisonSpectrum( FlipFrame.this.getComparisonSpectrum() );
            setCopyFlip( flipBox.isSelected() );
            setShiftRedShift( redshiftBox.isSelected() );
            setIncrement( incrementSpinner.getDoubleValue() );
            setOffset( (Double) offsetSpinner.getValue() );
            if ( spefoBox.isSelected() ) {
                setSPEFOValueText( spefoArea.getText() );
                setSPEFONoteText( spefoNotes.getText() );
            }
        }

        public void setComparisonSpectrum( EditableSpecData spectrum )
        {
            this.spectrum = spectrum;
        }

        public EditableSpecData getComparisonSpectrum()
        {
            return spectrum;
        }

        public void setCopyFlip( boolean state )
        {
            copyFlip = state;
        }

        public boolean isCopyFlip()
        {
            return copyFlip;
        }

        public void setShiftRedShift( boolean state )
        {
            shiftRedShift = state;
        }

        public boolean isShiftRedShift()
        {
            return shiftRedShift;
        }

        public void setIncrement( double value )
        {
            increment = value;
        }

        public double getIncrement()
        {
            return increment;
        }

        public void setOffset( Double value )
        {
            offset = value;
        }

        public Double getOffset()
        {
            return offset;
        }

        public void setSPEFOValueText( String text )
        {
            spefoValueText = text;
        }

        public String getSPEFOValueText()
        {
            return spefoValueText;
        }

        public void setSPEFONoteText( String text )
        {
            spefoNoteText = text;
        }

        public String getSPEFONoteText()
        {
            return spefoNoteText;
        }
    }

    /**
     * Inner class defining all local Actions.
     */
    protected class LocalAction
        extends AbstractAction
    {

        //  Types of action.
        public static final int CLOSE = 0;
        public static final int RESET = 1;
        public static final int COPY = 2;
        public static final int SPEFO = 3;
        public static final int SPEFOSAVE = 4;
        public static final int READVISITOR = 5;
        public static final int MAKEVIS = 6;

        //  The type of this instance.
        private int actionType = CLOSE;

        public LocalAction( int actionType, String name )
        {
            super( name );
            this.actionType = actionType;
        }

        public LocalAction( int actionType, String name, Icon icon )
        {
            super( name, icon );
            this.actionType = actionType;
        }

        public LocalAction( int actionType, String name, Icon icon,
                            String help )
        {
            this( actionType, name, icon );
            putValue( SHORT_DESCRIPTION, help );
        }

        public void actionPerformed( ActionEvent ae )
        {
            switch ( actionType )
            {
               case CLOSE: {
                   closeWindowEvent();
                   break;
               }
               case RESET: {
                   resetSelectedSpectrum();
                   visitor.clearStates();
                   break;
               }
               case COPY: {
                   copyFlipCurrentSpectrum();
                   break;
               }
               case SPEFO: {
                   makeSpefoChanges();
                   break;
               }
               case SPEFOSAVE: {
                   spefoSave();
                   break;
               }
               case READVISITOR: {
                   readVisitorLineList();
                   break;
               }
               case MAKEVIS: {
                   setVisitorLineList();
                   break;
               }
            }
        }
    }
}
