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
import swise.agents.communicator.Communicator;
import swise.agents.communicator.Information;
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
public class Human extends TrafficAgent implements Serializable, DiseaseVector {

	
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
	public static int activity_relax = 3;
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
	
		
	public GeoNode getNode() {return node;}
	
	/**
	 * Default Wrapper Constructor: provides the default parameters
	 * 
	 * @param id - unique string identifying the Human
	 * @param position - Coordinate indicating the initial position of the Human
	 * @param home - Coordinate indicating the Human's home location
	 * @param work - Coordinate indicating the Human's workplace
	 * @param world - reference to the containing ModernPump instance
	 */
	public Human(String id, Coordinate position, Coordinate home, ModernPump world){		
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
	public Human(String id, Coordinate position, Coordinate home, ModernPump world, double decayParam, double speed){

		super((new GeometryFactory()).createPoint(position));
		
		myID = id;
		this.world = world;
		this.isMovable = true;
		this.space = world.humanLayer;

		this.decayParam = decayParam;
		this.speed = speed;
		this.minSpeed = 650; // ~5mph

		this.home = home;
/*		// if provided with an appropriate home location, find the nearest node to that point and
		// save it
		if(home != null){
			Coordinate homePoint = this.snapPointToRoadNetwork(home);
			this.home = homePoint;
		}
		
		// LOCALIZE THE AGENT INITIALLY
		
		// find the closest edge to the Human initially (for ease of path-planning)
		edge = world.getClosestEdge(position);
		
		// if no such edge exists, there is a problem with the setup
		if(edge == null){ 
			System.out.println(this.myID + "\tINIT_ERROR");
			return;
		}

		// figure out the closest GeoNode to the Human's initial position
		GeoNode n1 = (GeoNode) edge.getFrom();
		GeoNode n2 = (GeoNode) edge.getTo();
		
		if(n1.geometry.getCoordinate().distance(position) <= n2.geometry.getCoordinate().distance(position))
			node = n1;
		else 
			node = n2;

		// do all the setup regarding the Human's position on the road segment
		segment = new LengthIndexedLine((LineString)((MasonGeometry)edge.info).geometry);
		startIndex = segment.getStartIndex();
		endIndex = segment.getEndIndex();
		currentIndex = segment.indexOf(position);

		// SCHEDULE THE AGENT'S VARIOUS PROCESSES
		*/
		// schedule the Human to check in and make decisions at the beginning of the simulation (with
		// ordering 100 so that it runs after the wildfire, etc)
		world.schedule.scheduleOnce(this, 100);
		
/*	/*	// schedule the Human to observe its immediate surroundings at least every hour, with ordering
		// 50 so that it always observes before making its decisions
		observer = world.schedule.scheduleRepeating(new Steppable (){
			private static final long serialVersionUID = 1L;

			@Override
			public void step(SimState state) {
				
				// the Human doesn't observe anything while it's asleep
				if(currentActivity == activity_sleep) return;
				
				// check for threats in the environment: if any are found,
				// call the decision-tree step from inside a wrapper

				int numThreats = knowledge.size(); // check check for new knowledge
				observe();
				if(knowledge.size() > numThreats)
					stepWrapper();
			}
		}, 50, 12);
	*/	
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
		
		myLastSpeed = -1; // reset this for accuracy in reporting
		
		// attempt to utilize the superclass's movement method
		int moveSuccess = super.navigate(resolution);
		
		return 1;
	}
	
	/**
	 * Based on the Human's current activity and time of day, pick its next activity
	 */
	void pickDefaultActivity(){

		// get the time
		int time = (int) world.schedule.getTime();

		// if super sick and 
		if(stress > illnessThreshold){
			Bag medicalCenters = world.medicalLayer.getObjectsWithinDistance(this.geometry, 1000);
			if(medicalCenters.size() > 0){
				headFor(((MasonGeometry)medicalCenters.get(0)).geometry.getCoordinate(), null);
			}
			else if(home.distance(geometry.getCoordinate()) > ModernPump.resolution){
				headFor(home, familiarRoadNetwork);
			}
			else{ // super sick, just stay in place and check back in an hour
				world.schedule.scheduleOnce(time + 12, 100 + world.random.nextInt(world.humans.size()), this);
				return;
			}
			world.schedule.scheduleOnce(time + 1, 100 + world.random.nextInt(world.humans.size()), this);
			return;
		}
		
		// if the Human is moving, keep moving! 
		if(currentActivity == activity_travel && path != null){
//			System.out.println("keep moving");

			navigate(ModernPump.resolution);
//			System.out.println(time + "\t" + (time + 1));
			world.schedule.scheduleOnce(time + 1, 100 + world.random.nextInt(world.humans.size()), this);
			return;
		}

		// if the Human is traveling but has reached the end of its path, either transition into working or relaxing at home
		else if(currentActivity == activity_travel && path == null){

//			System.out.println("transition at end of path");

			// if at work, start working
			if(geometry.getCoordinate().distance(targetDestination) <= ModernPump.resolution && targetDestination.distance(home) > ModernPump.resolution){
				currentActivity = activity_work;
				int nextTime = Math.max(time + 1, getTime(17, 6) + 6 - world.random.nextInt(13)); // random offset of up to an hour in either direction
//				System.out.println(time + "\t" + nextTime);
				world.schedule.scheduleOnce(nextTime, 100 + world.random.nextInt(world.humans.size()), this);				
			}
			// if at home, spend time at home
			else if(geometry.getCoordinate().distance(home) <= ModernPump.resolution){
				currentActivity = activity_relax;
				int nextTime = Math.max(time + 1, getTime(22,0) + 6 - world.random.nextInt(13));  // random offset of up to an hour in either direction
//				System.out.println(time + "\t" + nextTime);
				world.schedule.scheduleOnce(nextTime, 100 + world.random.nextInt(world.humans.size()), this);				
			}
			else {
				// reset path and head for target again!
				headFor(this.targetDestination, familiarRoadNetwork);
				navigate(ModernPump.resolution);
//				System.out.println(time + "\t" + (time + 1));
				world.schedule.scheduleOnce(time + 1, 100 + world.random.nextInt(world.humans.size()), this);
			}
			
			return;
		}

		// if the Human is just getting up in the morning, stay in house until time to leave
		else if(currentActivity == activity_sleep){
//			System.out.println("wakeup");

			currentActivity = activity_relax;
			int nextTime = Math.max(time + 1, getTime(7, 9) + 6 - world.random.nextInt(13));  // random offset of up to an hour in either direction
			world.schedule.scheduleOnce(nextTime, 100 + world.random.nextInt(world.humans.size()), this);
			return;
		}

		// if the Human has just gotten off work, head home
		else if(currentActivity == activity_work){
			
//			System.out.println("go home");

/*			if(this.geometry.getCoordinate().distance(targetDestination) <= ModernPump.resolution)
				path = (ArrayList <Edge>)this.familiarPaths.get(1).clone();
			else
	*/			path = null;
			
			this.currentActivity = this.activity_travel;
			headFor(home, familiarRoadNetwork);
			navigate(ModernPump.resolution);
//			System.out.println(time + "\t" + (time + 1));
			world.schedule.scheduleOnce(time + 1, 100 + world.random.nextInt(world.humans.size()), this);
			return;
		}
		
		// if it's time to go to bed, etc.
		else if(currentActivity == activity_relax && ((time % 288) >= 252)){ // it's after 9:00pm
//			System.out.println("go to sleep");

			currentActivity = activity_sleep;
			int nextTime = Math.max(time + 1, getTime(7, 0) + 6 - world.random.nextInt(13));  // random offset of up to an hour in either direction
			world.schedule.scheduleOnce(nextTime, 100 + world.random.nextInt(world.humans.size()), this);
			return;
		}
		
		// if it's time for work, go to work
		else if(currentActivity == activity_relax && ((time % 288) >= 87)){ // it's after 7:15am
			
//			System.out.println("go to place");

			path = null;		
			this.currentActivity = this.activity_travel;
			Coordinate destination = world.humans.get(world.random.nextInt(world.humans.size())).home;
			headFor(destination, familiarRoadNetwork);
//					(Coordinate)((GeoNode)world.majorRoadNodesLayer.getGeometries().get(world.random.nextInt(world.majorRoadNodesLayer.getGeometries().size()))).geometry.getCoordinate().clone(), familiarRoadNetwork);
			navigate(ModernPump.resolution);
//			System.out.println(time + "\t" + (time + 1));
			world.schedule.scheduleOnce(time + 1, 100 + world.random.nextInt(world.humans.size()), this);
		}

		// default for no other case
		else {
			System.out.println("PROBLEM WITH THIS AGENT");
			//world.schedule.scheduleOnce(time + 12, 100 + world.random.nextInt(world.humans.size()), this); // check again in an hour
		}
		
	}
	
	/**
	 * Return the timestep that will correspond with the next instance of the given hour:minute combination
	 * 
	 * @param desiredHour - the hour to find
	 * @param desiredMinuteBlock - the minute to find
	 * @return the timestep of the next hour:minute combination
	 */
	int getTime(int desiredHour, int desiredMinuteBlock){

		int result = 0;
		
		// the current time in the day
		int time = (int)(world.schedule.getTime());
		int numDaysSoFar = (int) Math.floor(time / 288);
		int currentTime = time % 288;

		int goalTime = desiredHour * 12 + desiredMinuteBlock;
		
		if(goalTime < currentTime)
			result = 288 * (numDaysSoFar + 1) + goalTime;
		else
			result = 288 * numDaysSoFar + goalTime;
		
		return result;
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
		
		// If there IS a problem  //////////////////
		else{
			
			int needInfo = doINeedMoreInformation();			
			System.out.println("I need info maybe!");
		}
	
		
		////////// Cleanup ////////////////////////////////////////////////////

		// update this Human's information, and possibly remove them from the simulation if they've
		// exited the bounds of the world
		lastMove = state.schedule.getTime();
	/*	if(path == null && !world.humanLayer.MBR.contains(this.geometry.getCoordinate())){
			removeMe();
			return;
		}
		*/
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
		
		// takes the Human out of the environment
		if(edge != null && edge instanceof ListEdge) 
			((ListEdge)edge).removeElement(this);
		world.humans.remove(this);
		
		// finally, reset position information
		this.updateLoc(new Coordinate(0,0)); // take me off the map, essentially
		world.resetLayers();
		return;
	}
	
	////////////////////////////////////////////////////////////////////////////////////////////////
	/////// ASSESSMENT /////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////////

	/**
	 * Determines whether the Human needs to seek out more information
	 * 
	 * @return  0 for "no further information necessary"
	 * 			1 for "further information necessary"
	 */
	public int doINeedMoreInformation(){

		// CHECKS

		// if there are no threats, this was called erroneously
		if(knowledge.size() == 0)
			return 0;
		
		else if(knowledge.entrySet().iterator().next().getValue().getTime() > world.schedule.getTime()) // HACK HACK HACK WHILE WILDFIRES ARE HACKED
			return 0;

		// otherwise everything should be ok, we can operate with this level of information
		return 0;
	} 
	

	/**
	 * Add new information to the Human's store of knowledge, and factor it into the Human's stress level
	 */
	public void learnAbout(Object o, Information info){
		knowledge.put(o, info);
		this.updateToStressLevel(info.getValence());
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


	

	/**
	 * Set up a course to take the Human to the given coordinates
	 * 
	 * @param place - the target destination
	 * @return 1 for success, -1 for a failure to find a path, -2 for failure based on the provided destination or current position
	 */
	int headFor(Coordinate place, Network roadNetwork) {

		// first, record from where the agent is starting
		startPoint = this.geometry.getCoordinate();
		goalPoint = null;

		double wanderThreshold = 10000000;
		if(startPoint.distance(place) < wanderThreshold){
			targetDestination = (Coordinate) place.clone();
			goalPoint = targetDestination;
			MasonGeometry mg = new MasonGeometry(world.fa.createLineString(new Coordinate[] {startPoint, goalPoint}));;
			mg.addStringAttribute("open", "OPEN");
			ListEdge e = new ListEdge(new Edge(new GeoNode(this.geometry), new GeoNode(world.fa.createPoint(place)), mg), mg.geometry.getLength());
			path = new ArrayList <Edge> ();
			path.add(e);
			direction = 1;
			node = (GeoNode) e.getFrom();
			LineString ls = (LineString)mg.geometry;
			segment = new LengthIndexedLine(ls);
			startIndex = segment.getStartIndex();
			endIndex = segment.getEndIndex();
			return 1;
		}
		
		// if the current node and the current edge don't match, there's a problem with the Human's understanding of its
		// current position
		if(!(edge.getTo().equals(node) || edge.getFrom().equals(node))){
			System.out.println( (int)world.schedule.getTime() + "\t" + this.myID + "\tMOVE_ERROR_mismatch_between_current_edge_and_node");
			return -2;
		}

		// FINDING THE GOAL //////////////////

		// set up goal information
		targetDestination = this.snapPointToRoadNetwork(place);
		
		GeoNode destinationNode = world.getClosestGeoNode(targetDestination);//place);
		if(destinationNode == null){
			System.out.println((int)world.schedule.getTime() + "\t" + this.myID + "\tMOVE_ERROR_invalid_destination_node");
			return -2;
		}

		// be sure that if the target location is not a node but rather a point along an edge, that
		// point is recorded
		if(destinationNode.geometry.getCoordinate().distance(targetDestination) > ModernPump.resolution)
			goalPoint = targetDestination;
		else
			goalPoint = null;


		// FINDING A PATH /////////////////////

		if(path == null)
			path = pathfinder.astarPath(node, destinationNode, roadNetwork);

		// if it fails, give up
		if (path == null){
			return -1;
		}

		// CHECK FOR BEGINNING OF PATH ////////

		// we want to be sure that we're situated on the path *right now*, and that if the path
		// doesn't include the link we're on at this moment that we're both
		// 		a) on a link that connects to the startNode
		// 		b) pointed toward that startNode
		// Then, we want to clean up by getting rid of the edge on which we're already located

		// Make sure we're in the right place, and face the right direction
		if (edge.getTo().equals(node))
			direction = 1;
		else if (edge.getFrom().equals(node))
			direction = -1;
		else {
			System.out.println((int)world.schedule.getTime() + "\t" + this.myID + "MOVE_ERROR_mismatch_between_current_edge_and_node_2");
			return -2;
		}

		// reset stuff
		if(path.size() == 0 && targetDestination.distance(geometry.getCoordinate()) > world.resolution){
			path.add(edge);
			node = (GeoNode) edge.getOtherNode(node); // because it will look for the other side in the navigation!!! Tricky!!
		}

		// CHECK FOR END OF PATH //////////////

		// we want to be sure that if the goal point exists and the Human isn't already on the edge 
		// that contains it, the edge that it's on is included in the path
		if (goalPoint != null) {// && path.size() > 0) {

			ListEdge myLastEdge = world.getClosestEdge(goalPoint);
			
			if(myLastEdge == null){
				System.out.println((int)world.schedule.getTime() + "\t" + this.myID + "\tMOVE_ERROR_goal_point_is_too_far_from_any_edge");
				return -2;
			}
			
			// make sure the point is on the last edge
			Edge lastEdge;
			if (path.size() > 0)
				lastEdge = path.get(0);
			else
				lastEdge = edge;

			Point goalPointGeometry = world.fa.createPoint(goalPoint);
			if(!lastEdge.equals(myLastEdge) && ((MasonGeometry)lastEdge.info).geometry.distance(goalPointGeometry) > ModernPump.resolution){
				if(lastEdge.getFrom().equals(myLastEdge.getFrom()) || lastEdge.getFrom().equals(myLastEdge.getTo()) 
						|| lastEdge.getTo().equals(myLastEdge.getFrom()) || lastEdge.getTo().equals(myLastEdge.getTo()))
					path.add(0, myLastEdge);
				else{
					System.out.println((int)world.schedule.getTime() + "\t" + this.myID + "\tMOVE_ERROR_goal_point_edge_is_not_included_in_the_path");
					return -2;
				}
			}
			
		}

		// set up the coordinates
		this.startIndex = segment.getStartIndex();
		this.endIndex = segment.getEndIndex();

		return 1;
	}



	/**
	 * Check the tile in which this Human finds itself to see if there's something on fire near it
	 * 
	 * (Could easily be extended to check for other things as well)
	 */
	void observe(){
		if(removed == true) return;
	}
		
	////////////////////////////////////////////////////////////////////////////////////////////////
	/////// end METHODS ////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////////

	
	////////////////////////////////////////////////////////////////////////////////////////////////
	/////// UTILITIES //////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////////
	
	
	/**
	 * Snap the coordinate to the nearest point on the road network
	 * 
	 * @param c - the point in question
	 * @return - nearest point on the road network
	 */
	public Coordinate snapPointToRoadNetwork(Coordinate c){
		ListEdge myEdge = null;
		double resolution = ModernPump.resolution;
		
		// if the network hasn't been properly set up, don't try to find something on it =\
		if(world.networkEdgeLayer.getGeometries().size() == 0) 
			return null;
		
		// while there's no edge, expand outward until the Human finds one
		while(myEdge == null){
			myEdge = world.getClosestEdge(c, resolution);
			resolution *= 10;
		}
		
		// having found a line, find the index of the point on that line
		LengthIndexedLine closestLine = new LengthIndexedLine((LineString) (((MasonGeometry)myEdge.info).getGeometry()));
		double myIndex = closestLine.indexOf(c);
		return closestLine.extractPoint(myIndex);
	}
	
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

	public ArrayList getInformationFrom() {
		// TODO Auto-generated method stub
		return null;
	}

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

	////////////////////////////////////////////////////////////////////////////////////////////////
	/////// end UTILITIES //////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////////
 
}