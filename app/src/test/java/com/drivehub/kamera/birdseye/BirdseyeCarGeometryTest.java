package com.drivehub.kamera.birdseye;

import org.junit.Test;
import static org.junit.Assert.*;

public class BirdseyeCarGeometryTest {
    @Test public void frontUsesNominalFootprintAndOnlyRearGetsDisplayExtension() {
        double x0=.10512996465,y0=.04582828283,x1=.89487004280,y1=.95276959240;
        BirdseyeCarGeometry g=new BirdseyeCarGeometry(768,1536,x0,y0,x1,y1);
        assertEquals(0,g.leftMm+(x0+x1)*g.widthMm/2,1e-3);
        assertEquals(BirdseyeMesh.CAR_WIDTH-2*BirdseyeCarGeometry.MASK_INSET_MM,(x1-x0)*g.widthMm,1e-3);
        assertEquals(-2143.5,g.topMm+y0*g.heightMm,1e-3);
        assertEquals(2323.5,g.topMm+y1*g.heightMm,1e-3);
        assertEquals(4467,(y1-y0)*g.heightMm,1e-3);
        // The original shadow is not moved rearwards with the larger artwork.
        assertEquals(4161.819055,1536*(y1-y0)*g.maskReferencePixelMm,1e-3);
    }
    @Test public void extraTransparentPaddingCannotChangeVisibleBodySize() {
        BirdseyeCarGeometry a=new BirdseyeCarGeometry(40,80,.2,.1,.8,.9);
        BirdseyeCarGeometry b=new BirdseyeCarGeometry(80,160,.35,.3,.65,.7);
        assertEquals(a.mmPerPixel,b.mmPerPixel,1e-5);
        assertEquals(a.leftMm+a.widthMm*.2,b.leftMm+b.widthMm*.35,1e-3);
        assertEquals(a.topMm+a.heightMm*.1,b.topMm+b.heightMm*.3,1e-3);
    }
    @Test public void malformedImageRegistrationIsRejected() {
        for(double[] bounds:new double[][]{{0,0,Double.NaN,1},{0,0,1,Double.POSITIVE_INFINITY},
                {-.1,0,1,1},{0,0,1.1,1},{.5,0,.5,1},{.8,0,.2,1},{0,0,.0001,1}}) {
            try {new BirdseyeCarGeometry(40,80,bounds[0],bounds[1],bounds[2],bounds[3]);fail();}
            catch(IllegalArgumentException expected){assertNotNull(expected.getMessage());}
        }
        try {new BirdseyeCarGeometry(0,80,0,0,1,1);fail();}
        catch(IllegalArgumentException expected){assertNotNull(expected.getMessage());}
    }
}
