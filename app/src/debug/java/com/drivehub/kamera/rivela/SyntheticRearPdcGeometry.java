package com.drivehub.kamera.rivela;

import com.drivehub.kamera.parking.PdcGeometry;
import java.util.List;

/** App debug adapter: four invented distances with red/orange/yellow/green test colors. */
public final class SyntheticRearPdcGeometry extends PdcGeometry {
    public SyntheticRearPdcGeometry() {
        super(level->level==3?80:level==6?110:level==9?140:-1);
    }
    @Override public synchronized List<PdcBars.Bar> bars(CarFrame car,long nowMs) {
        List<PdcBars.Bar> bars=super.bars(car,nowMs);
        if(bars.size()==4) {
            PdcBars.Bar second=bars.get(1);
            // Same animation/geometry; this adapter shares the package to access the original Bar constructor.
            bars.set(1,new PdcBars.Bar(0xFFFF9800,second.widthMm,second.xy));
        }
        return bars;
    }
}
