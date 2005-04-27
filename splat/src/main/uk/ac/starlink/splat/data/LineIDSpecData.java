/*
 * Copyright (C) 2003 Central Laboratory of the Research Councils
 *
 *  History:
 *     11-APR-2003 (Peter W. Draper):
 *       Original version.
 */
package uk.ac.starlink.splat.data;

import java.awt.Rectangle;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.IOException;
import java.util.Arrays;

import uk.ac.starlink.ast.Grf;
import uk.ac.starlink.ast.Plot;
import uk.ac.starlink.ast.Mapping;
import uk.ac.starlink.ast.FrameSet;
import uk.ac.starlink.ast.grf.DefaultGrf;
import uk.ac.starlink.ast.grf.DefaultGrfState;
import uk.ac.starlink.splat.util.SplatException;
import uk.ac.starlink.splat.ast.ASTChannel;

/**
 * A type of EditableSpecData that draws a string at a "spectral"
 * position. The expected use of these facilities is for identifying
 * line positions, (TODO: as a vertical bar marker, plus ) as a string
 * (TODO: at some orientation and scale).
 *
 * @author Peter W. Draper
 * @version $Id$
 */
public class LineIDSpecData
    extends EditableSpecData
    implements Serializable
{
    /**
     * Reference to the LineIDSpecDataImpl.
     */
    protected transient LineIDSpecDataImpl lineIDImpl = null;

    /**
     * Reference to an associated SpecData.
     */
    protected transient SpecData specData = null;

    /**
     * Serialised form of the data labels, usually null.
     */
    protected String[] serializedLabels = null;

    /**
     * Serialization version ID string.
     */
    static final long serialVersionUID = -332954044517171663L;

    /** TODO: Undoable support */

    /**
     * Constructor, takes a LineIDSpecDataImpl.
     */
    public LineIDSpecData( LineIDSpecDataImpl lineIDImpl )
        throws SplatException
    {
        super( lineIDImpl );
        this.lineIDImpl = lineIDImpl;
        setRange();               // Deferred from super constructor
        useInAutoRanging = false; // by default.
        setPointSize( 1.0 );
    }

    /**
     * Constructor, takes a LineIDSpecDataImpl and a SpecData
     * instance. The SpecData instance is used to supply the relative
     * positions of the labels.
     */
    public LineIDSpecData( LineIDSpecDataImpl lineIDImpl,
                           SpecData specData )
        throws SplatException
    {
        this( lineIDImpl );
        setSpecData( specData );
    }

    /**
     * Set the SpecData used to defined the relative positioning for
     * the labels.
     */
    public void setSpecData( SpecData specData )
    {
        this.specData = specData;
    }

    /**
     * Get the SpecData in use. If none then null is returned.
     */
    public SpecData getSpecData()
    {
        return specData;
    }

    /**
     * Get the labels.
     */
    public String[] getLabels()
    {
        if ( lineIDImpl != null ) {
            return lineIDImpl.getLabels();
        }
        return null;
    }

    /**
     * Set the labels.
     */
    public void setLabels( String[] labels )
        throws SplatException
    {
        lineIDImpl.setLabels( labels );
    }

    /**
     * Set a specific label.
     */
    public void setLabel( int index, String label )
    {
        lineIDImpl.setLabel( index, label );
    }

    /**
     * Return if the backing implementation has valid positions for
     * the labels.
     */
    public boolean haveDataPositions()
    {
        return lineIDImpl.haveDataPositions();
    }

    // Override setRange as the typical line id spectrum will not have data
    // values.
    public void setRange()
    {
        if ( lineIDImpl == null ) return;

        if ( haveDataPositions() ) {
            super.setRange();
            return;
        }

        double xMin = Double.MAX_VALUE;
        double xMax = -Double.MAX_VALUE;
        for ( int i = xPos.length - 1; i >= 0 ; i-- ) {
            if ( xPos[i] != SpecData.BAD ) {
                if ( xPos[i] < xMin ) {
                    xMin = xPos[i];
                }
                if ( xPos[i] > xMax ) {
                    xMax = xPos[i];
                }
            }
        }
        if ( xMin == Double.MAX_VALUE ) {
            xMin = 0.0;
        }
        if ( xMax == -Double.MAX_VALUE ) {
            xMax = 0.0;
        }

        //  Record plain range.
        range[0] = xMin;
        range[1] = xMax;
        range[2] = -1.0;
        range[3] = 1.0;

        //  And the "full" version.
        fullRange[0] = xMin;
        fullRange[1] = xMax;
        fullRange[2] = -1.0;
        fullRange[3] = 1.0;
    }

    /**
     * Draw the "spectrum". In this case it means draw the line id
     * strings.
     */
    public void drawSpec( Grf grf, Plot plot, double[] limits,
                          boolean physical )
    {
        //  Set up clip region if needed.
        Rectangle cliprect = null;
        if ( limits != null ) {
            double[][] clippos = null;
            if ( physical ) {
                clippos = astJ.astTran2( plot, limits, false );
                cliprect =
                    new Rectangle( (int) clippos[0][0],
                                   (int) clippos[1][1],
                                   (int) ( clippos[0][1] - clippos[0][0] ),
                                   (int) ( clippos[1][0] - clippos[1][1] ) );
            }
            else {
                cliprect = new Rectangle( (int) limits[0], (int) limits[3],
                                          (int) ( limits[2] - limits[0] ),
                                          (int) ( limits[1] - limits[3] ) );

                clippos = astJ.astTran2( plot, limits, true );

                //  Transform limits to physical for positioning of labels.
                limits[0] = clippos[0][0];
                limits[1] = clippos[1][0];
                limits[2] = clippos[0][1];
                limits[3] = clippos[1][1];
            }
        }
        else {
            // Cannot have null limits.
            limits = new double[4];
        }

        //  Get all labels.
        String[] labels = getLabels();
        double yshift = 0.1 * ( limits[3] - limits[1] );

        //  Need to generate positions for placing the labels. The
        //  various schemes for this are use any positions read from
        //  the implementation, use the positions from a SpecData and
        //  finally put them along the given limits.
        double[] xypos = new double[xPos.length * 2];
        double[] ypos = yPos;
        if ( ! haveDataPositions() ) {
            if ( specData != null ) {
                ypos = specData.getYData();
            }
            else {
                ypos = null;
            }
        }
        if ( ypos != null ) {
            for ( int i = 0, j = 0; j < xPos.length; j++, i += 2 ) {
                xypos[i] = xPos[j];
                if ( ypos[j] == BAD ) {
                    xypos[i + 1] = limits[1] + yshift;
                }
                else {
                    xypos[i + 1] = ypos[j] + yshift;
                }
            }
        }
        else {
            for ( int i = 0, j = 0; j < xPos.length; j++, i += 2 ) {
                xypos[i] = xPos[j];
                xypos[i + 1] = limits[1] + yshift; // or limits[3]?
            }
        }

        //  Define general parameters. Note these are "overridden" by
        //  the Plot when "strings" parameters are set (allows the
        //  selection of colour using the same buttons as for a normal
        //  spectrum). The text style can be set using the "strings"
        //  configuration options of the plot (which is why we use it
        //  and not the Grf object, which bypasses the Plot).
        DefaultGrf defaultGrf = (DefaultGrf) grf;
        DefaultGrfState oldState = setGrfAttributes( defaultGrf, false );
        defaultGrf.setClipRegion( cliprect );

        float[] up = new float[2];
        up[0] = 1.0F;
        up[1] = 0.0F;
        double[] pos = new double[2];

        for ( int i = 0, j = 0; i < labels.length; i++, j += 2 ) {
            pos[0] = xypos[j];
            pos[1] = xypos[j+1];
            plot.text( labels[i], pos, up, "CC" );
        }
        resetGrfAttributes( defaultGrf, oldState, false );
        defaultGrf.setClipRegion( null );
    }

//
//  Serializable interface.
//

    private void writeObject( ObjectOutputStream out )
        throws IOException
    {
        //  Need to write out persistent data labels.
        serializedLabels = getLabels();

        //  And store all member variables.
        out.defaultWriteObject();

        //  Finished.
        serializedLabels = null;
    }

    private void readObject( ObjectInputStream in )
        throws IOException, ClassNotFoundException
    {
        //  Note we use this method as we need a different impl object from
        //  SpecData and need to restore the data labels.
        try {
            // Restore state of member variables.
            in.defaultReadObject();

            //  Create the backing impl, this will supercede one created by
            //  the SpecData readObject.
            LineIDMEMSpecDataImpl newImpl =
                new LineIDMEMSpecDataImpl( shortName, this );
            fullName = null;

            //  Restore data labels.
            if ( serializedLabels != null ) {
                newImpl.setLabels( serializedLabels );
                serializedLabels = null;
            }
            this.lineIDImpl = newImpl;
            this.editableImpl = newImpl;
            this.impl = newImpl;

            //  Full reset of state.
            readData();
        }
        catch ( SplatException e ) {
            e.printStackTrace();
        }
    }
}
