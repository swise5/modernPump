package modernPump.agents;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Random;

import modernPump.agents.diseases.Disease;
import modernPump.sim.ModernPump;
import sim.engine.SimState;
import sim.engine.Steppable;
import sim.engine.Stoppable;
import sim.field.network.Edge;
import sim.field.network.Network;
import sim.util.Bag;
import sim.util.geo.AttributeValue;
import sim.util.geo.MasonGeometry;
import sim.util.geo.PointMoveTo;
import swise.agents.communicator.Communicator;
import swise.agents.communicator.Information;
import swise.agents.MobileAgent;
import swise.agents.SpatialAgent;
import swise.agents.TrafficAgent;
import swise.objects.NetworkUtilities;
import swise.objects.network.GeoNode;
import swise.objects.network.ListEdge;

import com.vividsolutions.jts.geom.*;
import com.vividsolutions.jts.linearref.LengthIndexedLine;


/**
 * Human object. Contains attributes, makes decisions, communicates, moves, etc. 
 * 
 */
public class HumanTeleporter extends TrafficAgent implements Serializable, DiseaseVector {

	
	private static final long serialVersionUID = 1L;

	////////// Objects ///////////////////////////////////////
	ModernPump world;
	
	Stoppable stopper = null;
	boolean removed = false;
	boolean alive = true;

	////////// Activities ////////////////////////////////////

	int currentActivity = 0;
	
	public static int activity_travel = 1;
	public static int activity_work = 2;
	public static int activity_sleep = 4;
	
	////////// Attributes ///////////////////////////////////

	String myID;
	
	Coordinate home, water;
	
	Stoppable observer = null;
	Stoppable mediaUser = null;
	
	HashMap <String, Disease> diseases = new HashMap <String, Disease> ();
	
	// Time checks
	double lastMove = -1;

	// weighted by familiarity (and more familiar with major highways, etc)
	// this should be updated when Human finds out about fires!
	public Network familiarRoadNetwork = null;
	ArrayList <ArrayList<Edge>> familiarPaths = new ArrayList <ArrayList <Edge>> ();
	
	public HashMap <Object, Information> knowledge = new HashMap <Object, Information> ();

	HashMap <Integer, Integer> sentimentSignal = new HashMap <Integer, Integer> ();
	
	double size = 3;
	
	Coordinate targetDestination = null;

	double stress = 0; // between 0 and 10, 10 being super stressed out in a bad way
	
	////////// Parameters ///////////////////////////////////

	double decayParam = .5;
	double illnessThreshold = 5;
	
	////////// END Parameters ///////////////////////////////
	
		
	/**
	 * Default Wrapper Constructor: provides the default parameters
	 * 
	 * @param id - unique string identifying the Human
	 * @param position - Coordinate indicating the initial position of the Human
	 * @param home - Coordinate indicating the Human's home location
	 * @param work - Coordinate indicating the Human's workplace
	 * @param world - reference to the containing ModernPump instance
	 */
	public HumanTeleporter(String id, Coordinate position, Coordinate home, ModernPump world){		
		this(id, position, home, world, .5, 800);
	}
	
	/**
	 * Specialized constructor: use to specify parameters for an Human
	 * 
	 * @param id - unique string identifying the Human
	 * @param position - Coordinate indicating the initial position of the Human
	 * @param home - Coordinate indicating the Human's home location
	 * @param work - Coordinate indicating the Human's workplace
	 * @param world - reference to the containing ModernPump instance
	 * @param decayParam - parameter indicating rate of decay of influence of stressful information
	 * 		on the Agents' stress level
	 * @param speed - speed at which the Human moves through the environment (m per 5 min)
	 */
	public HumanTeleporter(String id, Coordinate position, Coordinate home, ModernPump world, double decayParam, double speed){

		super((new GeometryFactory()).createPoint(position));
		
		myID = id;
		this.world = world;
		this.isMovable = true;
		this.space = world.humanLayer;

		this.decayParam = decayParam;

		this.home = home;
		this.speed = speed;

		// schedule the Human to check in and make decisions at the beginning of the simulation (with
		// ordering 100 so that it runs after the wildfire, etc)
		world.schedule.scheduleOnce(this, 100);
		
		// set the Human's initial activity to be sleeping
		currentActivity = activity_sleep;
		
		// add the Human to the space
		space.addGeometry(this);
		
		
		// set the Human to not initially be evacuating
		this.addIntegerAttribute("Sick", 0);		
	}

	/**
	 * Navigate
	 * 
	 * Attempt to move along the existing path. If the road is impassible, a RoadClosure 
	 * is raised and the Human attempts to replan its path to its target. If that replanning 
	 * fails, the Human defaults to trying to wander toward its target.
	 */
	public int navigate(double resolution){
		
		// update the heatmap, as the Human is moving (or trying to, at least)
		//world.incrementHeatmap(this.geometry);

		if(targetDestination != null)
			this.updateLoc(this.targetDestination);
		targetDestination = null;
		
		return 1;
	}
	
	Coordinate pickPlaceToVisit(){
		
		Coordinate destination = world.humans.get(world.random.nextInt(world.humans.size())).home;
		double stdDev = world.random.nextGaussian();
		double distanceStdDev = 5000 * Math.abs(stdDev), distanceMin = 5000 * (Math.abs(stdDev) - 1);
//		if(distanceStdDev >= 30000)
//			System.out.println(distanceStdDev / 3.);
		int tries = 0;
		while(destination.distance(home) > distanceStdDev || destination.distance(home) < distanceMin){
			destination = world.humans.get(world.random.nextInt(world.humans.size())).home;
			tries++;
			if(tries > 1000) 
				distanceMin = 0;
			if(tries > 5000)
				distanceStdDev = Double.MAX_VALUE;
		}

		return destination;
	}
	
	/**
	 * Based on the Human's current activity and time of day, pick its next activity
	 */
	void pickDefaultActivity(){

		// get the time
		int time = (int) world.schedule.getTime();

		// if super sick 
		if(stress > illnessThreshold && currentActivity != activity_travel){
			Bag medicalCenters = world.medicalLayer.getObjectsWithinDistance(this.geometry, 1000);
			if(medicalCenters.size() > 0){
				currentActivity = activity_travel;
				headFor(((MasonGeometry)medicalCenters.get(0)).geometry.getCoordinate(), null);
			}
			else if(home.distance(geometry.getCoordinate()) > ModernPump.resolution){
				currentActivity = activity_travel;
				headFor(home, familiarRoadNetwork);
			}
			else{ // super sick, just stay in place and check back in an hour
				currentActivity = activity_sleep;
				world.schedule.scheduleOnce(time + 12, 100 + world.random.nextInt(world.humans.size()), this);
				return;
			}
			int timeDiff = (int) Math.max(1,  geometry.getCoordinate().distance(targetDestination) / this.speed);
			world.schedule.scheduleOnce(time + timeDiff, 100 + world.random.nextInt(world.humans.size()), this);
			return;
		}
		
		// if the Human is moving, keep moving! 
		if(currentActivity == activity_travel && targetDestination != null){
		//	System.out.println("move");
			navigate(ModernPump.resolution);
			world.schedule.scheduleOnce(time + 1, 100 + world.random.nextInt(world.humans.size()), this);
			targetDestination = null;
			currentActivity = activity_work;
			return;
		}

		// if the Human is traveling but has reached the end of its path, either transition into working or relaxing at home
		else if(currentActivity == activity_travel && targetDestination == null){


			// if at work, start working
			if(geometry.getCoordinate().distance(home) > ModernPump.resolution){
	//			System.out.println("transition at end of path");
				if(stress > illnessThreshold && (world.medicalLayer.getObjectsWithinDistance(this.geometry, ModernPump.resolution).size() > 0)){
					
					System.out.println(this.myID + " CHECKED INTO MEDICAL FACILITY");
					if(world.random.nextDouble() < .05) {
						System.out.println(this.myID + " DIED");
						this.removeMe();
						return;
					}
				
					stress = 0;
					world.schedule.scheduleOnce(time + 24, this);
					return;
				}
				else {
					currentActivity = activity_work;
					int nextTime = Math.max(time + 1, 1 + world.random.nextInt(4)); // random offset of up to an hour in either direction
					world.schedule.scheduleOnce(nextTime, 100 + world.random.nextInt(world.humans.size()), this);
				}
			}
			// if at home, spend time at home
			else if(geometry.getCoordinate().distance(home) <= ModernPump.resolution && time % 24 > 18){
		//		System.out.println("transition at end of path");
				currentActivity = activity_sleep;
				int nextTime = time + Math.max(1, 24 - (time % 24) - world.random.nextInt(3) + 9); // should be: next day, plus aobut 8 hrs
				world.schedule.scheduleOnce(nextTime, 100 + world.random.nextInt(world.humans.size()), this);
				return;
			}
			else {
				this.currentActivity = this.activity_travel;
				headFor(pickPlaceToVisit(), familiarRoadNetwork);
				int timeDiff = (int) Math.max(1,  geometry.getCoordinate().distance(targetDestination) / this.speed);
				world.schedule.scheduleOnce(time + timeDiff, 100 + world.random.nextInt(world.humans.size()), this);
				return;

			}
			
			return;
		}

		// if the Human is just getting up in the morning, stay in house until time to leave
		else if(currentActivity == activity_sleep){
	//		System.out.println("wakeup");

			this.currentActivity = this.activity_travel;
			headFor(pickPlaceToVisit(), familiarRoadNetwork);
			int timeDiff = (int) Math.max(1,  geometry.getCoordinate().distance(targetDestination) / this.speed);
			world.schedule.scheduleOnce(time + timeDiff, 100 + world.random.nextInt(world.humans.size()), this);
			return;
		}

		// if the Human has just gotten off work, head home
		else if(currentActivity == activity_work){
			
	//		System.out.println("go home");

			this.currentActivity = this.activity_travel;
			headFor(home, familiarRoadNetwork);
			navigate(ModernPump.resolution);
			world.schedule.scheduleOnce(time + 1, 100 + world.random.nextInt(world.humans.size()), this);
			return;
		}
		
		// default for no other case
		else {
			System.out.println("PROBLEM WITH THIS AGENT");
		}
		
	}
		
	/**
	 * Check in on the Human and run its decision tree. Schedule when next to check in.
	 */
	@Override
	public void step(SimState state) {
		
		////////// Initial Checks ///////////////////////////////////////////////
		
		if(removed)
			return;
		
		// make sure the Human is only being called once per tick
		if(lastMove >= state.schedule.getTime()) return;
		
		
		////////// BEHAVIOR //////////////////////////////////////////////////////
		
		// If there IS NOT a problem ///////////////
		if(knowledge.size() == 0){
			pickDefaultActivity();
		}
		
		////////// Cleanup ////////////////////////////////////////////////////

		// update this Human's information, and possibly remove them from the simulation if they've
		// exited the bounds of the world
		lastMove = state.schedule.getTime();
	}
	
	/**
	 * Tidies up after the Human and removes all possible traces of it from the simulation
	 */
	void removeMe(){
		
		// internal record-keeping
		removed = true;
//		observer.stop();
		lastMove = world.schedule.getTime() + 1;
		
		if(stopper != null)
			stopper.stop();
		
		world.humans.remove(this);
		
		// finally, reset position information
		this.updateLoc(new Coordinate(0,0)); // take me off the map, essentially
		world.resetLayers();
		return;
	}
	
	/**
	 * Factor a new piece of information with a set amount of stress into the Human's stress level

	 * @param valence - the degree to which the new information is stressful
	 */
	void updateToStressLevel(int valence){
		
		int time = (int)world.schedule.getTime();
		if(!sentimentSignal.containsKey(time)) // if no other information this tick, save it
			sentimentSignal.put(time, valence);
		else if(valence > sentimentSignal.get(time)) // else, if this is the most stressful info this tick, save it
			sentimentSignal.put(time, valence);
	}


	void headStraightTo(Coordinate place){
		
		targetDestination = (Coordinate) place.clone();
		
		// attempt to find path
		ArrayList <LineString> pathComponents = clearPath(this.geometry.getCoordinate(), targetDestination);
		path = new ArrayList <Edge> ();
		for(int i = 0; i < pathComponents.size(); i++){
			MasonGeometry mg = new MasonGeometry(pathComponents.get(i));
			mg.addStringAttribute("open", "OPEN");
			ListEdge e = new ListEdge(new Edge(new GeoNode(this.geometry), new GeoNode(world.fa.createPoint(place)), mg), mg.geometry.getLength());
			path.add(e);
		}
		
		
		// clean up
		direction = 1;
		node = (GeoNode) path.get(0).getFrom();
		segment = new LengthIndexedLine(pathComponents.get(0));
		startIndex = segment.getStartIndex();
		endIndex = segment.getEndIndex();
	}
	
	ArrayList <LineString> clearPath(Coordinate startPoint, Coordinate endPoint){
		LineString ls = world.fa.createLineString(new Coordinate[] {startPoint, endPoint});
		ArrayList <LineString> result = new ArrayList <LineString> ();
		if(ls.crosses(world.landArea)){
			double distance = startPoint.distance(endPoint);
			Coordinate midPoint = new Coordinate(startPoint.x + .5 * (endPoint.x - startPoint.x), startPoint.y + .5 * (endPoint.y - startPoint.y));
			//Geometry g = ls.intersection(world.landArea);
			Coordinate c = midPoint;//g.getCoordinate();
			double halfDistance = distance / 2.;
			Coordinate b = new Coordinate(c.x + halfDistance - world.random.nextInt((int)distance), c.y + halfDistance - world.random.nextInt((int)distance));
			Point p = world.fa.createPoint(b);
			while(!world.landArea.contains(p)){
				b = new Coordinate(c.x + halfDistance - world.random.nextInt((int)distance), c.y + halfDistance - world.random.nextInt((int)distance));
				p = world.fa.createPoint(b);
			}
			result.addAll( clearPath(startPoint, b));
			result.addAll( clearPath(b, endPoint));
			return result;
		}

		result.add(ls);
		return result;
	}
	

	/**
	 * Set up a course to take the Human to the given coordinates
	 * 
	 * @param place - the target destination
	 * @return 1 for success, -1 for a failure to find a path, -2 for failure based on the provided destination or current position
	 */
	int headFor(Coordinate place, Network roadNetwork) {

		// first, record from where the agent is starting
		startPoint = this.geometry.getCoordinate();
		targetDestination = (Coordinate) place.clone();
		//headStraightTo(place);
		return 1;
	}

	////////////////////////////////////////////////////////////////////////////////////////////////
	/////// end METHODS ////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////////

	
	////////////////////////////////////////////////////////////////////////////////////////////////
	/////// UTILITIES //////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////////
	

	/**
	 * Comparator
	 */
	public boolean equals(Object o){
		if(!(o instanceof Human)) return false;
		else 
			return ((Human)o).myID.equals(myID);
	}
	
	/** HashCode */
	public int hashCode(){ return myID.hashCode(); }

	public String toString(){ return myID; }
	
	// GETTERS
	public Coordinate getHome(){ return home; }
	public int getActivity(){ return this.currentActivity; }
	public double getValence(){ return this.stress; }
	
	/**  Wrapper around step, so that it can be called from other functions */
	void stepWrapper(){ this.step(world); }

	@Override
	public void acquireDisease(final Disease d) {
		d.setHost(this);
		d.start(world.schedule);
		diseases.put(d.getName(), d);
		this.addIntegerAttribute("Sick", 1);
		System.out.println(myID + " INFECTED");
		stress = 1;
	}

	@Override
	public void loseDisease(Disease d) {
		// start running again!
		world.schedule.scheduleOnce(world.schedule.getTime() + 1, 100 + world.random.nextInt(world.humans.size()), this);
		stress = 1;
	}
	
	public boolean infectedWith(String diseaseName){
		if(diseases.containsKey(diseaseName)) return true;
		else return false;
	}

	@Override
	public boolean stillExists() {
		return this.alive;
	}

	@Override
	public void changeStage(int stage) {
		this.addIntegerAttribute("Sick", stage);
		if(stage == 1)
			stress = 3;
		else if(stage == 2)
			stress = 7;
	}

	@Override
	public void receiveTreatment(String type) {
		// TODO Auto-generated method stub
		
	}

	/**
	 * Change the position of the MobileAgent in the space in which it is embedded
	 * @param c - the new position of the MobileAgent
	 */
	protected void updateLoc(Coordinate c){
		PointMoveTo p = new PointMoveTo();
		p.setCoordinate(c);
		geometry.apply(p);
		geometry.geometryChanged();
	}

	@Override
	public Geometry getGeometry() {
		return geometry;
	}

	////////////////////////////////////////////////////////////////////////////////////////////////
	/////// end UTILITIES //////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////////
 
}