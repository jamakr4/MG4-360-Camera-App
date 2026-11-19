package com.drivehub.kamera.birdseye;

/** Immutable front-wheel angle pair, positive right. Sensor conversion belongs to the steering model. */
public final class WheelSteering {
    public static final int MAX_DEGREES=35;
    public static final class Angles {
        public final float left, right;
        public final double curvaturePerMm;
        public final boolean guidanceAvailable;
        public final com.drivehub.kamera.steering.WheelGuides.Direction guidanceDirection;
        private Angles(float left,float right){this(left,right,Double.NaN,false);}
        private Angles(float left,float right,double curvature,boolean available){this(left,right,curvature,available,null);}
        private Angles(float left,float right,double curvature,boolean available,com.drivehub.kamera.steering.WheelGuides.Direction direction){this.left=left;this.right=right;curvaturePerMm=curvature;guidanceAvailable=available;guidanceDirection=direction;}
        public float forPart(String name){return "front_left".equals(name)?left:"front_right".equals(name)?right:0;}
    }
    private volatile Angles angles=new Angles(0,0);
    /** Compatibility for the existing visual mapping; new consumers read the whole pair. */
    public float degrees(){Angles a=angles;return (a.left+a.right)/2;}
    public Angles angles(){return angles;}
    public void setDegrees(float value){if(Float.isFinite(value)){float angle=Math.max(-MAX_DEGREES,Math.min(MAX_DEGREES,value));setAngles(angle,angle);}}
    public void setAngles(float left,float right){
        if(Float.isFinite(left)&&Float.isFinite(right)&&Math.abs(left)<90&&Math.abs(right)<90)
            angles=new Angles(left,right);
    }
    /** Publish wheels, curvature and availability together so a GL frame cannot mix sample times. */
    public void setSolution(com.drivehub.kamera.steering.SteeringModel.Solution solution,boolean available){
        setSolution(solution,available,null);
    }
    /** Live gear selection travels with the curvature and availability in the same publication. */
    public void setSolution(com.drivehub.kamera.steering.SteeringModel.Solution solution,boolean available,
                            com.drivehub.kamera.steering.WheelGuides.Direction direction){
        if(solution.valid)angles=new Angles(solution.leftDegrees,solution.rightDegrees,solution.curvaturePerMm,available,direction);
        else {Angles held=angles;angles=new Angles(held.left,held.right,held.curvaturePerMm,false,direction);}
    }
}
