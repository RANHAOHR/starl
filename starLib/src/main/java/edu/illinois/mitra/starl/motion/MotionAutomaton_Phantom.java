package edu.illinois.mitra.starl.motion;

//import android.util.Log;

import java.util.Arrays;

import edu.illinois.mitra.starl.gvh.GlobalVarHolder;
import edu.illinois.mitra.starl.interfaces.RobotEventListener.Event;
import edu.illinois.mitra.starl.models.Model_Phantom;
import edu.illinois.mitra.starl.objects.ItemPosition;
import edu.illinois.mitra.starl.objects.ObstacleList;

//import edu.illinois.mitra.starl.models.Model_quadcopter;

/**
 * Created by VerivitalLab on 2/19/2016.
 */
public class MotionAutomaton_Phantom extends RobotMotion {
    protected static final String TAG = "MotionAutomaton";
    protected static final String ERR = "Critical Error";
    final int safeHeight = 150;
    private boolean landed = true;

    protected GlobalVarHolder gvh;
    protected DjiController bti;

    // Motion tracking
    protected ItemPosition destination;
    private Model_Phantom mypos; // //todo complete model for phantom

    //PID controller parameters
    double saturationLimit = 50;
    double windUpLimit = 185;
    int filterLength = 8;
    /*double Kpx = 0.2;
    double Kpy = 0.2;
    double Kix = 0.04;
    double Kiy = 0.04;
    double Kdx = 0.4;
    double Kdy = 0.45;*/
    // the ones below work pretty well
    double Kpx = 0.0314669809792096; //314....
    double Kpy = 0.0314669809792096;
    double Kix = 0.0110786899216426; //011...
    double Kiy = 0.0110786899216426;
    double Kdx = 0.159205037832174; //113....
    double Kdy = 0.159205037832174;

    PIDController PID_x = new PIDController(Kpx, Kix, Kdx, saturationLimit, windUpLimit, filterLength);
    PIDController PID_y = new PIDController(Kpy, Kiy, Kdy, saturationLimit, windUpLimit, filterLength);


    protected enum STAGE {
        INIT, MOVE, HOVER, TAKEOFF, LAND, GOAL, STOP
    }

    private STAGE next = null;
    protected STAGE stage = STAGE.INIT;
    private STAGE prev = null;
    protected boolean running = false;
    boolean colliding = false;

    private enum OPMODE {
        GO_TO
    }

    private OPMODE mode = OPMODE.GO_TO;

    private static final MotionParameters DEFAULT_PARAMETERS = MotionParameters.defaultParameters();
    private volatile MotionParameters param = DEFAULT_PARAMETERS;
    //need to pass some more parameteres into this param
    //	MotionParameters.Builder settings = new MotionParameters.Builder();


    //	private volatile MotionParameters param = settings.build();

    public MotionAutomaton_Phantom(GlobalVarHolder gvh, DjiController bti) {
        super(gvh.id.getName());
        this.gvh = gvh;
        this.bti = bti;
    }

    public void goTo(ItemPosition dest, ObstacleList obsList) {
        goTo(dest);
    }

    public void goTo(ItemPosition dest) {
        if(!inMotion || !this.destination.equals(dest)) {
            done = false;
            this.destination = new ItemPosition(dest.name,dest.x,dest.y,0); //Todo(TIM) add dest.z?
            gvh.log.d(TAG, "Going to X: " + Integer.toString(dest.x) + ", Y: " + Integer.toString(dest.y));
      //      Log.d(TAG, "Going to X: " + Integer.toString(dest.x) + ", Y: " + Integer.toString(dest.y));
            //this.destination = dest;
            this.mode = OPMODE.GO_TO;
            startMotion();
        }
    }

    @Override
    public synchronized void start() {
        super.start();
        gvh.log.d(TAG, "STARTED!");
    }

    @Override
    public void run() {
        super.run();
        gvh.threadCreated(this);
        while(true) {
            //			gvh.gps.getObspointPositions().updateObs();
            if(running) {
                ItemPosition temp = gvh.gps.getMyPosition();
                mypos = (Model_Phantom)temp; // TD_NATHAN: check. I fixed it.
   //             if(mypos == null) { continue;}
                // if you change to 3D waypoints, use distanceTo instead of distanceTo2D
                int distance = mypos.distanceTo2D(destination);
                colliding = false;

                if(!colliding && stage != null) {
                    if(stage != prev)
                        gvh.log.e(TAG, "Stage is: " + stage.toString());

                    switch(stage) {
                        case INIT:
                            if(mode == OPMODE.GO_TO) {
                                PID_x.reset();
                                PID_y.reset();
                                setMaxTilt(2.5f); // TODO: add max tilt to motion paramters class
                                if(landed){
                                    next = STAGE.TAKEOFF;
                                }
                                else{
                                    if(distance <= param.GOAL_RADIUS) {
                                        next = STAGE.GOAL;
                                    }
                                    else{
                                        next = STAGE.MOVE;
                                    }
                                }
                            }
                            break;
                        case MOVE:
                            if(landed){
                                takeOff();
                                next = STAGE.TAKEOFF;
                                break;
                            }
                            if(distance <= param.GOAL_RADIUS) {
                                next = STAGE.GOAL;
                            }
                            else{
                                double rollCommand = PID_x.getCommand(mypos.x, destination.x);
                                double pitchCommand = PID_y.getCommand(mypos.y, destination.y);
                                double yawCommand = calculateYaw();
                                double gazCommand = 0;
                                setControlInputRescale(yawCommand, pitchCommand, rollCommand, gazCommand);
                                // TD_NATHAN: check and resolve: was mypos.angle
                                // that was the correct solution, has been resolved
                            }
                            break;
                        case HOVER:
                            if(distance <= param.GOAL_RADIUS) {
                                hover();
                                double yawCommand = calculateYaw();
                                setControlInputRescale(yawCommand, 0, 0, 0);
                            }
                            else{
                                double rollCommand = PID_x.getCommand(mypos.x, destination.x);
                                double pitchCommand = PID_y.getCommand(mypos.y, destination.y);
                                double yawCommand = calculateYaw();
                                double gazCommand = 0;
                                setControlInputRescale(yawCommand, pitchCommand, rollCommand, gazCommand);
                            }
                            break;
                        case TAKEOFF:
                            takeOff();
                            landed = false;
                            next = STAGE.MOVE;
                            break;
                        case LAND:
                            land();
                            break;
                        case GOAL:
                            done = true;
                            gvh.log.i(TAG, "At goal!");
                            if(param.STOP_AT_DESTINATION){
                                next = STAGE.HOVER;
                            }
                            //running = false;
                            inMotion = false;
                            break;
                        case STOP:
                            motion_stop();
                    }
                    if(next != null) {
                        prev = stage;
                        stage = next;
                        System.out.println("Stage transition to " + stage.toString() + "previous stage is "+ prev);

                        gvh.log.i(TAG, "Stage transition to " + stage.toString());
                        gvh.trace.traceEvent(TAG, "Stage transition", stage.toString(), gvh.time());
                    }
                    next = null;
                }

                if((colliding || stage == null) ) {
                    //land(); todo(tim) address collisions
                    //stage = STAGE.LAND;
                }
            }
            gvh.sleep(param.AUTOMATON_PERIOD);
        }
    }

    public void cancel() {
        running = false;
        land();
    }

    public void takePicture(){}

    @Override
    public void rotateGimbal(float y) {

    }

    @Override
    public void rotateGimbal(float p, float y) {

    }

    @Override
    public void rotateGimbal(float p, float y, float r) {

    }

    @Override
    public void downloadPhotos() {

    }

    ;

    @Override
    public void motion_stop() {
        land();
        stage = STAGE.LAND;
        this.destination = null;
        running = false;
        inMotion = false;
    }

    @Override
    public void motion_resume() {
        running = true;
    }

    private void startMotion() {
        running = true;
        stage = STAGE.INIT;
        inMotion = true;
    }

    protected void sendMotionEvent(int motiontype, int... argument) {
        // TODO: This might not be necessary
        gvh.trace.traceEvent(TAG, "Motion", Arrays.toString(argument), gvh.time());
        gvh.sendRobotEvent(Event.MOTION, motiontype);
    }

    protected void setControlInput(double yaw_v, double pitch, double roll, double gaz){
        //Bluetooth command to control the drone
        bti.setInputs((float)yaw_v, (float)pitch, (float)roll, (float)gaz);
        gvh.log.i(TAG, "control input as, yaw, pitch, roll, thrust " + yaw_v + ", " + pitch + ", " +roll + ", " +gaz);
    }

    /**
     *  	take off from ground
     */
    protected void takeOff(){
        //Bluetooth command to control the drone
        bti.sendTakeoff();
        gvh.log.i(TAG, "Drone taking off");
    }

    /**
     * land on the ground
     */
    protected void land(){
        //Bluetooth command to control the drone
        bti.sendLanding();
        gvh.log.i(TAG, "Drone landing");
    }

    /**
     * hover at current position
     */
    protected void hover(){
        //Bluetooth command to control the drone
        bti.hover();
        gvh.log.i(TAG, "Drone hovering");
    }

    private double calculateYaw() {
        // this method calculates a yaw correction, to keep the drone's yaw angle near 90 degrees
        if(mypos.yaw > 90) {
            return -5;
        }
        else if(mypos.yaw < 90) {
            return 5;
        }
        else {
            return 0;
        }
    }

     protected void setMaxTilt(float val) {
        bti.setMaxTilt(val);
    }

    @Override
    public void turnTo(ItemPosition dest) {
        throw new IllegalArgumentException("quadcopter does not have a corresponding turn to");
    }

    @Override
    public void setParameters(MotionParameters param) {
        // TODO Auto-generated method stub
    }

    private void setControlInputRescale(double yaw_v, double pitch, double roll, double gaz){
        setControlInput(rescale(yaw_v, 50), rescale(pitch, 50), rescale(roll, 50), rescale(gaz, 50));
    }
    private void setControlInputRescaleD(double yaw_v, double pitch, double roll, double gaz){
        setControlInput(rescale(yaw_v, mypos.max_yaw_speed), rescale(pitch, mypos.max_pitch_roll), rescale(roll, mypos.max_pitch_roll), rescale(gaz, mypos.max_gaz));
    }

    private double rescale(double value, double max_value){
        if(Math.abs(value) > max_value){
            return (Math.signum(value));
        }
        else{
            return value/max_value;
        }
    }

}
