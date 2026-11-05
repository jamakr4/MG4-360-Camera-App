package com.drivehub.kamera.camera;

import org.junit.Test;
import static org.junit.Assert.*;

public class IpkRoutePolicyTest {
    @Test public void navigationIsDefaultAndDoesNotAlsoShowOnMedia() {
        assertEquals(IpkRoutePolicy.NAVIGATION, IpkRoutePolicy.normalizeScreen(0));
        assertEquals(IpkRoutePolicy.NAVIGATION, IpkRoutePolicy.normalizeScreen(99));
        assertTrue(IpkRoutePolicy.isSelectedScreen(1, IpkRoutePolicy.NAVIGATION));
        assertFalse(IpkRoutePolicy.isSelectedScreen(2, IpkRoutePolicy.NAVIGATION));
    }

    @Test public void mediaSelectionOnlyShowsOnMedia() {
        assertTrue(IpkRoutePolicy.isSelectedScreen(2, IpkRoutePolicy.MEDIA));
        assertFalse(IpkRoutePolicy.isSelectedScreen(1, IpkRoutePolicy.MEDIA));
    }

    @Test public void unknownAndOtherPagesNeverReceiveTheMirror() {
        assertFalse(IpkRoutePolicy.isSelectedScreen(null, IpkRoutePolicy.NAVIGATION));
        assertFalse(IpkRoutePolicy.isSelectedScreen(null, IpkRoutePolicy.MEDIA));
        for (int tab : new int[]{0, 3, 4, 5, 6, 7}) {
            assertFalse(IpkRoutePolicy.isSelectedScreen(tab, IpkRoutePolicy.NAVIGATION));
            assertFalse(IpkRoutePolicy.isSelectedScreen(tab, IpkRoutePolicy.MEDIA));
        }
    }

    @Test public void fullRearImageFitsInTheOemCameraArea() {
        assertArrayEquals(new int[]{722, 126, 260}, IpkRoutePolicy.bounds(1280, 480));
        assertEquals(173, IpkRoutePolicy.previewHeight(260));
        assertArrayEquals(new int[]{0, 0, 100}, IpkRoutePolicy.bounds(100, 100));
    }
}
