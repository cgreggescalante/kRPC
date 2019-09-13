import krpc.client.Connection;
import krpc.client.RPCException;
import krpc.client.Stream;
import krpc.client.StreamException;
import krpc.client.services.KRPC;
import krpc.client.services.SpaceCenter;
import krpc.client.services.SpaceCenter.Flight;
import krpc.client.services.SpaceCenter.ReferenceFrame;
import krpc.client.services.SpaceCenter.Resources;
import krpc.client.services.SpaceCenter.Vessel;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class Autopilot {
    private Connection connection;
    private KRPC krpc;
    private SpaceCenter spaceCenter;
    private Vessel vessel;

    private ReferenceFrame refFrame;
    private Flight flight;

    private Stream<Double> ut;
    private Stream<Double> altitude;
    private Stream<Double> apoapsis;

    private Autopilot() throws IOException, RPCException, krpc.client.StreamException, InterruptedException {
        connection = Connection.newInstance();
        krpc = KRPC.newInstance(connection);
        System.out.println("Connected to kRPC version " + krpc.getStatus().getVersion());

        spaceCenter = SpaceCenter.newInstance(connection);
        vessel = spaceCenter.getActiveVessel();
        System.out.println("Current Vessel : " + vessel.getName());

        refFrame = vessel.getSurfaceReferenceFrame();
        flight = vessel.flight(refFrame);

        ut = connection.addStream(SpaceCenter.class, "getUT");
        altitude = connection.addStream(flight, "getMeanAltitude");
        apoapsis = connection.addStream(vessel.getOrbit(), "getApoapsisAltitude");


        Ascend();
    }

    private void Ascend() throws RPCException, StreamException, InterruptedException {
        float targetAltitude = 75000;
        System.out.println(vessel.getControl().getCurrentStage());

        // Pre-launch setup
        vessel.getControl().setSAS(false);
        vessel.getControl().setRCS(false);
        vessel.getControl().setThrottle(1);

        // Activate the first stage
        vessel.getControl().activateNextStage();
        vessel.getAutoPilot().engage();
        vessel.getAutoPilot().targetPitchAndHeading(90, 90);

        Resources boosterResources = vessel.resourcesInDecoupleStage(vessel.getControl().getCurrentStage() - 1, false);
        Stream<Float> srbFuel = connection.addStream(boosterResources, "amount", "SolidFuel");

        // Main ascent loop
        boolean srbsSeparated = false;
        boolean stage1Separated = false;
        double turnAngle = 0;

        while (true) {
            // Gravity turn
            double frac = getAltitude() / 15000;
            double newTurnAngle = frac * 45.0;
            System.out.print(""); // Essential magic
            if (Math.abs(newTurnAngle - turnAngle) > 0.5) {
                turnAngle = newTurnAngle;
                vessel.getAutoPilot().targetPitchAndHeading((float) (90 - turnAngle), 90);
            }
            // Separate SRBs when finished
            if (!srbsSeparated && srbFuel.get() == 0) {
                TimeUnit.MILLISECONDS.sleep(250);
                vessel.getControl().activateNextStage();
                srbsSeparated = true;
                System.out.println("SRBs separated");
            }

            if (getAltitude() > 15000) {
                System.out.println("Beginning Phase 2");
                break;
            }
        }

        vessel.getAutoPilot().disengage();
        vessel.getAutoPilot().setSAS(true);
        vessel.getAutoPilot().setSASMode(SpaceCenter.SASMode.PROGRADE);
        Resources stage1Resources = vessel.resourcesInDecoupleStage(vessel.getControl().getCurrentStage() - 1, false);
        Stream<Float> stage1Fuel = connection.addStream(stage1Resources, "amount", "LiquidFuel");

        while (true)
        {
            if (getApoapsis() > targetAltitude * 0.9)
            {
                System.out.println("Approaching target apoapsis");
                vessel.getControl().setThrottle((float) 0.5);
                break;
            }
            if (!stage1Separated && stage1Fuel.get() < .1)
            {
                vessel.getControl().activateNextStage();
                vessel.getControl().activateNextStage();
                stage1Separated = true;
                System.out.println("Stage 1 separated");
            }
        }

        while (true)
        {
            if (getApoapsis() > targetAltitude)
            {
                vessel.getControl().setThrottle(0);
                break;
            }
        }
        double timeTill = vessel.getOrbit().getTimeToApoapsis();
        SpaceCenter.Node circularize = vessel.getControl().addNode(ut.get()+timeTill, 1000, 0, 0);
    }

    private Double getAltitude() throws RPCException, StreamException
    {
        return altitude.get();
    }

    private Double getApoapsis() throws RPCException, StreamException
    {
        return apoapsis.get();
    }

    public static void main(String[] args) throws IOException, RPCException, krpc.client.StreamException, InterruptedException {
        new Autopilot();
    }
}
