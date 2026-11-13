package org.privacybubble.app.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public final class LocationReadLeaseTest {
    @Test public void wallClockRollbackCannotExtendAccess() {
        LocationReadLease lease=new LocationReadLease();
        assertTrue(lease.install(105000,100000,3000));
        assertTrue(lease.isValid(100001,3001));
        assertFalse(lease.isValid(90000,8000));
        assertEquals(0,lease.remaining(90000,8000));
    }
    @Test public void expiresAtTheBoundaryAndCannotInstallAnExpiredResponse() {
        LocationReadLease lease=new LocationReadLease();
        assertTrue(lease.install(104000,100000,3000));
        assertEquals(1,lease.remaining(103999,6999));
        assertFalse(lease.isValid(104000,6999));
        assertFalse(lease.install(100000,100000,3000));
        assertFalse(lease.active());
        assertFalse(lease.install(106000,100000,3000));
    }
    @Test public void delayedRenewalAndClearDoNotRestoreOldAccess() {
        LocationReadLease lease=new LocationReadLease();
        assertTrue(lease.install(104000,100000,3000));
        assertFalse(lease.isValid(104001,7001));
        assertTrue(lease.install(108000,104001,7001));
        lease.clear();
        assertFalse(lease.isValid(104002,7002));
        assertFalse(lease.install(Long.MAX_VALUE,100000,3000));
        assertFalse(lease.install(104000,100000,Long.MAX_VALUE));
    }
}
