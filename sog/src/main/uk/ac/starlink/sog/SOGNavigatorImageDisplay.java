/*
 * Copyright (C) 2002 Central Laboratory of the Research Councils
 *
 *  History:
 *     13-JUN-2002 (Peter W. Draper):
 *       Original version.
 */
package uk.ac.starlink.sog;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import javax.media.jai.JAI;
import javax.media.jai.PlanarImage;
import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;

import jsky.coords.CoordinateConverter;
import jsky.coords.WorldCoordinateConverter;
import jsky.image.ImageProcessor;
import jsky.image.gui.ImageHistoryItem;
import jsky.image.fits.codec.FITSImage;
import jsky.navigator.NavigatorImageDisplay;
import jsky.util.FileUtil;
import jsky.util.gui.DialogUtil;

import org.w3c.dom.Element;

import uk.ac.starlink.ast.Frame;
import uk.ac.starlink.ast.FrameSet;
import uk.ac.starlink.ast.Plot;
import uk.ac.starlink.ast.gui.ConfigurationStore;
import uk.ac.starlink.ast.gui.GraphicsHints;
import uk.ac.starlink.ast.gui.GraphicsHintsControls;
import uk.ac.starlink.ast.gui.PlotConfiguration;
import uk.ac.starlink.ast.gui.PlotConfigurator;
import uk.ac.starlink.ast.gui.PlotController;
import uk.ac.starlink.jaiutil.HDXImage;
import uk.ac.starlink.jaiutil.HDXImageProcessor;
import uk.ac.starlink.ndx.Ndx;
import uk.ac.starlink.ndx.Ndxs;

import uk.ac.starlink.sog.photom.SOGCanvasDraw;
import uk.ac.starlink.sog.photom.AperturePhotometryFrame;

/**
 * Extends NavigatorImageDisplay (and DivaMainImageDisplay) to add
 * support for reading HDX files.
 *
 * @author Peter W. Draper
 * @version $Id$
 */

public class SOGNavigatorImageDisplay
    extends NavigatorImageDisplay
    implements PlotController
{
    /**
     * Reference to the HDXImage displaying the NDX, if any.
     */
    protected HDXImage hdxImage = null;

    /**
     * Whether we're drawing a grid or not.
     */
    protected boolean drawGrid = false;

    /**
     * Simple counter for generating unique names.
     */
    private int counter= 0;

    /**
     * Specialized CanvasDraw.
     */
    protected SOGCanvasDraw sogCanvasDraw = null;

    /**
     * True when a NDX is loading. Used to enable certain events that
     * are only performed for files or urls (which an NDX may not have).
     */
    private boolean ndxLoading = false;

    //  Repeat all constructors.
    public SOGNavigatorImageDisplay( Component parent )
    {
        //  Use an ImageProcessor with HDX support.
        super( parent, new HDXImageProcessor() );

        //  Add our CanvasDraw.
        sogCanvasDraw = new SOGCanvasDraw( this );
        setCanvasDraw( sogCanvasDraw );
    }

    /**
     * Set the filename.... Whole method lifted from
     * DivaMainImageDisplay as we need to control creation differently
     * to how JSky works... XXX need to track any changes.
     */
    public void setFilename( String fileOrUrl )
    {
        if ( fileOrUrl.startsWith( "http:" ) ) {
            setURL( FileUtil.makeURL( null, fileOrUrl ) );
            return;
        }
        if ( !checkSave() ) {
            return;
        }

        addToHistory();
        _filename = fileOrUrl;
        _url = _origURL = FileUtil.makeURL( null, fileOrUrl );

        // free up any previously opened FITS images
        FITSImage fitsImage = getFitsImage();
        if (fitsImage != null) {
            fitsImage.close();
            fitsImage.clearTileCache();
            fitsImage = null;
        }

        // Do same for the HdxImage?
        if ( hdxImage != null ) {
            //hdxImage.close();
            hdxImage.clearTileCache();
            hdxImage = null;
        }

        // load non FITS and HDX images with JAI, others use more
        // efficienct implementations.

        if ( isJAIImageType( _filename ) ) {
            try {
                setImage( JAI.create( "fileload", _filename ) );
            }
            catch ( Exception e ) {
                DialogUtil.error( e );
                _filename = null;
                _url = _origURL = null;
                clear();
            }
        }
        else if ( _filename.endsWith( "xml" ) ||
                  _filename.endsWith( "sdf" )  ) {
            System.out.println( "Loading NDX: " + _filename );
            //  HDX/NDF
            try {
                hdxImage = new HDXImage( _filename );
                initHDXImage( hdxImage );
                setImage( PlanarImage.wrapRenderedImage( hdxImage ) );
            }
            catch ( IOException e ) {
                DialogUtil.error( e );
                _filename = null;
                _url = _origURL = null;
                clear();
            }
        }
        else {
            //  FITS Image. 13/01/03 is much faster than HDX FITS access...
            try {
                fitsImage = new FITSImage(_filename);
                initFITSImage(fitsImage);
                setImage(fitsImage);
            }
            catch (Exception e) {
                // fall back to JAI method
                try {
                    setImage(JAI.create("fileload", _filename));
                }
                catch (Exception ex) {
                    DialogUtil.error(e);
                    _filename = null;
                    _url = _origURL = null;
                    clear();
                }
            }
        }
        updateTitle();
    }

    /**
     * Accept an DOM element that contains an NDX for display. NDX
     * equivalent of setFilename.
     */
    public void setNDX( Element element )
    {
        //  Make sure currently displayed image is saved and added to
        //  history.
        if ( !checkSave() ) {
            return;
        }
        addToHistory();

        //  Create and load the PlanarImage that wraps the HDXImage,
        //  that accepts the DOM NDX!
        try {
            PlanarImage im =
                PlanarImage.wrapRenderedImage( new HDXImage( element, 0 ) );
            ndxLoading = true;
            setImage(im);
            ndxLoading = false;
        }
        catch( Exception e ) {
            DialogUtil.error( e );
            clear();
        }
        updateTitle();

        // Set the cut levels. TODO: Still not great.
        ImageProcessor ip = getImageProcessor();
        //ip.setCutLevels( ip.getMinValue(), ip.getMaxValue() );
        ip.autoSetCutLevels( getVisibleArea() );
        ip.update();
    }

    /**
     * Update the enabled states of some menu/toolbar actions.
     */
    protected void updateEnabledStates()
    {
        super.updateEnabledStates();
        if ( ndxLoading ) {
            getCutLevelsAction().setEnabled( true );
            getColorsAction().setEnabled( true );
        }
        if ( gridAction != null ) {
            gridAction.setEnabled( getCutLevelsAction().isEnabled() );
        }
    }


    /**
     * This method is called before and after a new image is loaded,
     * each time with a different argument.
     *
     * @param before set to true before the image is loaded and false
     *               afterwards
     */
    protected void newImage( boolean before )
    {
        if ( before ) {
            if ( hdxImage != null ) {
                hdxImage.clearTileCache();
            }
            hdxImage = null;
            setWCS( null );
        }
        else if ( getFitsImage() == null ) {

            // Check if it is a HDX, and if so, get the HDXImage
            // object which is needed to initialize WCS.
            PlanarImage im = getImage();
            if ( im != null ) {
                Object o = im.getProperty("#ndx_image");
                if ( o != null && (o instanceof HDXImage) ) {
                    hdxImage = (HDXImage) o;
                    initWCS();
                }
            }
        }

        // Do this afterwards so graphics are not re-drawn until WCS is
        // available.
        super.newImage( before );
    }

    /**
     * Called after a new HDXImage object was created to do HDX
     * specific initialization.
     */
    protected void initHDXImage( HDXImage hdxImage)
        throws IOException
    {
        // If the user previously set the image scale, restore it here
        // to avoid doing it twice
        ImageHistoryItem hi = getImageHistoryItem( new File(_filename) );
        float scale;
        if ( hi != null ) {
            scale = hi.getScale();
        }
        else {
            scale = getScale();
        }
        if ( scale != 1.0F ) {
            hdxImage.setScale( scale );
        }
    }

    /**
     * Initialize the world coordinate system, if the image properties
     * (keywords) support it
     */
    protected void initWCS()
    {
        if ( getFitsImage() != null ) {
            super.initWCS();
            return;
        }

        // If not FITS and not HDX nothing to do.
        if ( hdxImage == null ) {
            return;
        }

        // Only initialize once per image.
        if ( getWCS() != null ) {
            return;
        }

        //  Access the current NDX and see if it has an AST
        //  FrameSet. This can be used to construct an AstTransform
        //  that can be used instead of a WCSTransform.
        Ndx ndx = hdxImage.getCurrentNDX();
        try {
            FrameSet frameSet = Ndxs.getAst( ndx );
            if ( frameSet != null ) {
                setWCS( new AstTransform( frameSet, getImageWidth(),
                                          getImageHeight() ) );
            }
        }
        catch( Exception e ) {
            e.printStackTrace();
            setWCS( null );
            return;
        }
        if ( ! getWCS().isWCS() ) {
            setWCS( null );
        }
    }

    /**
     * Get the current NDX being displayed.
     */
    public Ndx getCurrentNdx()
    {
        return hdxImage.getCurrentNDX();
    }

    /**
     * Add an action to draw or remove a grid overlay.
     */
    private AbstractAction gridAction =
        new AbstractAction( "Grid" )
        {
            public void actionPerformed( ActionEvent evt )
            {
                AbstractButton b = (AbstractButton) evt.getSource();
                if ( b.isSelected() ) {
                    showGridControls();
                }
                else {
                    withdrawGridControls();
                }
            }
        };
    public AbstractAction getGridAction()
    {
        return gridAction;
    }

    protected PlotConfigurator plotConfigurator = null;
    protected PlotConfiguration plotConfiguration = new PlotConfiguration();
    protected GraphicsHints graphicsHints = new GraphicsHints();

    /**
     * Display a window for controlling the appearance of the grid.
     */
    protected void showGridControls()
    {
        if ( plotConfigurator == null ) {
            plotConfigurator =
                new PlotConfigurator( "Grid Overlay Configuration",
                                      this, plotConfiguration,
                                      "jsky", "PlotConfig.xml" );

            //  Add in extra graphics hints controls (anti-aliasing).
            plotConfiguration.add( graphicsHints );
            plotConfigurator.addExtraControls
                ( new GraphicsHintsControls( graphicsHints ), true );

            //  Set the default configuration of the controls.
            InputStream defaultConfig =
                getClass().getResourceAsStream( "defaultgrid.xml" );
            ConfigurationStore store = new ConfigurationStore( defaultConfig );
            Element defaultElement = store.getState( 0 );
            plotConfigurator.setDefaultState( defaultElement );
            plotConfigurator.reset();
            try {
                defaultConfig.close();
            }
            catch (IOException e) {
                // Do nothing.
            }
        }
        plotConfigurator.setVisible( true );
    }

    /**
     * Erase the grid, if drawn.
     */
    public void withdrawGridControls()
    {
        drawGrid = false;
        if ( astPlot != null ) {
            astPlot.clear();
            repaint();
        }
        if ( plotConfigurator != null ) {
            plotConfigurator.setVisible( false );
        }
    }

    protected Plot astPlot = null;

    /**
     * Create an AST Plot matched to the image and draw a grid overlay.
     */
    public void doPlot()
    {
        // Check we have a transform and it understands AST (codecs
        // for non-NDX types will in general not).
        WorldCoordinateConverter transform = getWCS();
        if ( transform == null ||
             ! getWCS().isWCS() ||
             ! ( transform instanceof AstTransform ) ) {
            System.out.println( "Not an AstTransform" );
            astPlot = null;
            return;
        }
        System.out.println( "Is an AstTransform" );
        CoordinateConverter cc = getCoordinateConverter();
        FrameSet frameSet = ((AstTransform)transform).getFrameSet();

        //  Use the limits of the image to determine the graphics
        //  position.
        double[] canvasbox = new double[4];
        Point2D.Double p = new Point2D.Double();

        p.setLocation( 1.0, 1.0 );
        cc.imageToScreenCoords( p, false );
        canvasbox[0] = p.x;
        canvasbox[1] = p.y;

        p.setLocation( getImageWidth(), getImageHeight() );
        cc.imageToScreenCoords( p, false );
        canvasbox[2] = p.x;
        canvasbox[3] = p.y;

        int xo = (int) Math.min( canvasbox[0], canvasbox[2] );
        int yo = (int) Math.min( canvasbox[1], canvasbox[3] );
        int dw = (int) Math.max( canvasbox[0], canvasbox[2] ) - xo;
        int dh = (int) Math.max( canvasbox[1], canvasbox[3] ) - yo;
        Rectangle graphRect = new Rectangle( xo, yo, dw, dh );

        //  Transform these positions back into image coordinates.
        //  These are suitably "untransformed" from the graphics
        //  position and should be the bottom-left and top-right
        //  corners.
        double[] basebox = new double[4];
        p = new Point2D.Double();
        p.setLocation( (double) xo, (double) (yo + dh) );
        cc.screenToImageCoords( p, false );
        basebox[0] = p.x;
        basebox[1] = p.y;

        p.setLocation( (double) (xo + dw), (double) yo );
        cc.screenToImageCoords( p, false );
        basebox[2] = p.x;
        basebox[3] = p.y;

        //  Now create the astPlot, clearing existing graphics first.
        if ( astPlot != null ) astPlot.clear();
        astPlot = new Plot( frameSet, graphRect, basebox );

        String options = plotConfiguration.getAst();
        astPlot.set( options );
        astPlot.grid();
    }

    //  Called when image needs repainting...
    public synchronized void paintLayer(Graphics2D g2D, Rectangle2D region)
    {
        super.paintLayer( g2D, region );

        //  Redraw a grid if required.
        if ( drawGrid ) {

            //  Draw the plot.
            doPlot();
            if ( astPlot != null ) {

                //  Apply rendering hints.
                graphicsHints.applyRenderingHints( g2D );

                // And paint.
                astPlot.paint( g2D );
            }
        }
    }

    //  The exit method needs finer control. Do not close the whole
    //  application when being tested.
    public void exit()
    {
        if ( doExit ) {
            super.exit();
        }
        else {
            Window w = SwingUtilities.getWindowAncestor( this );
            if ( w != null ) {
                w.setVisible( false );
            }
            else {
                setVisible( false );
            }
        }
    }

    private boolean doExit = true;
    public void setDoExit( boolean doExit )
    {
        this.doExit = doExit;
    }
    public boolean isDoExit()
    {
        return doExit;
    }

//
// PlotController interface.
//
    /**
     * Draw a grid over the currently displayed image.
     */
    public void updatePlot()
    {
        drawGrid = true;
        repaint();
    }

    /**
     * Set the image background colour.
     */
    public void setPlotColour( Color color )
    {
        setBackground( color );
    }

    /**
     * Return a reference to the Frame used to define the
     * current coordinates. Using the WCS FrameSet.
     */
    public Frame getPlotCurrentFrame()
    {
        WorldCoordinateConverter transform = getWCS();
        if ( transform == null ||
             ! getWCS().isWCS() ||
             ! ( transform instanceof AstTransform ) ) {
            return new Frame( 2 ); // Cannot return a null.
        }
        return (Frame) ((AstTransform)transform).getFrameSet();
    }

    //
    // Aperture photometry toolbox
    //
    /**
     * Add an action to draw or remove a grid overlay.
     */
    private AbstractAction photometryAction =
        new AbstractAction( "Photometry" )
        {
            public void actionPerformed( ActionEvent evt )
            {
                AbstractButton b = (AbstractButton) evt.getSource();
                if ( b.isSelected() ) {
                    showPhotomControls();
                }
                else {
                    withdrawPhotomControls();
                }
            }
        };
    public AbstractAction getPhotomAction()
    {
        return photometryAction;
    }

    /**
     * Erase the grid, if drawn.
     */
    public void withdrawPhotomControls()
    {
        if ( photometryWindow != null ) {
            photometryWindow.setVisible( false );
        }
    }

    /**
     * Display a window for performing aperture photometry.
     */
    protected void showPhotomControls()
    {
        if ( photometryWindow == null ) {
            photometryWindow = new AperturePhotometryFrame( this );
        }
        photometryWindow.setVisible( true );
    }
    private AperturePhotometryFrame photometryWindow = null;

    /**
     * Set the scale (zoom factor) for the image.
     * This also adjusts the origin so that the center of the image
     * remains about the same.
     */
    public void setScale( float scale )
    {
        super.setScale( scale );

        //  HDX speed ups? Done after super-class, which should take
        //  care of setting new scale and centering.
        if ( hdxImage != null ) {
            boolean needsUpdate = false;
            try {
                needsUpdate = hdxImage.setScale( scale );
                setPrescaled(  hdxImage.getSubsample() != 1 );
            }
            catch(IOException e) {
                DialogUtil.error(e);
            }
            if ( ! needsUpdate && scale == getScale() ) {
                return;
            }
            if ( needsUpdate ) {
                ImageProcessor ip = getImageProcessor();
                ip.setSourceImage( PlanarImage.wrapRenderedImage( hdxImage ),
                                   ip );
                ip.update();
            }
        }
    }
}
