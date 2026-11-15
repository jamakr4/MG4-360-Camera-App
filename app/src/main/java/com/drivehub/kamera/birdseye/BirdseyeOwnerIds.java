package com.drivehub.kamera.birdseye;
import java.util.concurrent.atomic.AtomicInteger;
/** Shared ID space for visible views, snapshots and the independent exclusive lease. */
final class BirdseyeOwnerIds {
    private static final AtomicInteger NEXT=new AtomicInteger(1);
    static int next(){int id=NEXT.getAndIncrement();if(id>1000000)throw new IllegalStateException("Camera owner IDs exhausted");return id;}
    private BirdseyeOwnerIds(){}
}
