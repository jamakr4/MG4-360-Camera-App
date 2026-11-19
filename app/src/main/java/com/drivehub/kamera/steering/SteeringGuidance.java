package com.drivehub.kamera.steering;

/** Single UI-thread state for live wheels and 2D/3D guidance, evaluated once before each display frame. */
public final class SteeringGuidance {
    private final SteeringModelPose pose=new SteeringModelPose(new SteeringModel());
    public static final class Frame {
        public final SteeringModel.Solution wheels;
        public final boolean steeringAvailable,guidanceAvailable;
        public final WheelGuides.Direction direction;
        private Frame(SteeringModelPose.Frame pose,WheelGuides.Direction direction,boolean active) {
            wheels=pose.displayed;steeringAvailable=pose.available;this.direction=direction;
            guidanceAvailable=active&&pose.available&&direction!=null;
        }
    }
    public Frame update(SteeringSnapshot steering,GearSnapshot gear,boolean active,long nowMs) {
        return new Frame(pose.update(steering,nowMs),gear.direction(nowMs),active);
    }
    public void reset(){pose.reset();}
}
