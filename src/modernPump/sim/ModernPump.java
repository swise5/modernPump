package modernPump.sim;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.FileLock;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map.Entry;

import sim.engine.SimState;
import sim.engine.Steppable;
import sim.field.geo.GeomGridField;
import sim.field.geo.GeomGridField.GridDataType;
import sim.field.geo.GeomVectorField;
import sim.field.grid.Grid2D;
import sim.field.grid.IntGrid2D;
import sim.field.network.Edge;
import sim.field.network.Network;
import sim.io.geo.ShapeFileImporter;
import sim.io.geo.ArcInfoASCGridImporter;
import sim.util.Bag;
import sim.util.geo.AttributeValue;
import sim.util.geo.GeomPlanarGraph;
import sim.util.geo.MasonGeometry;
import sim.util.geo.PointMoveTo;
import swise.agents.communicator.Communicator;
import swise.agents.communicator.Information;
import swise.disasters.Wildfire;
import swise.objects.AStar;
import swise.objects.NetworkUtilities;
import swise.objects.PopSynth;
import swise.objects.network.GeoNode;
import swise.objects.network.ListEdge;
import modernPump.agents.Human;
import modernPump.agents.HumanTeleporter;
import modernPump.agents.diseases.Cholera;
import modernPump.agents.diseases.Disease;

import org.jfree.data.xy.XYSeries;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.CoordinateSequenceFilter;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.geom.Point;

import ec.util.MersenneTwisterFast;

/**
 * Hotspots is the core of a simulation which projects the behavior of agents in the aftermath
 * of an incident.
 * 
 * @author swise
 *
 */
public class ModernPump extends SimState {

	/////////////// Model Parameters ///////////////////////////////////
	
	private static final long serialVersionUID = 1L;
	public int grid_width = 800;
	public int grid_height = 500;
	public static double resolution = 5;// the granularity of the simulation 
				// (fiddle around with this to merge nodes into one another)

	double decayParam = -1;
	double speed = -1;

	public double infectionProb = .30;
	
	/////////////// Data Sources ///////////////////////////////////////
	
	String dirName = "data/";
	
	//// END Data Sources ////////////////////////
	
	/////////////// Containers ///////////////////////////////////////

	public GeomVectorField baseLayer = new GeomVectorField(grid_width, grid_height);
	public GeomVectorField roadLayer = new GeomVectorField(grid_width, grid_height);
	public GeomVectorField waterwayLayer = new GeomVectorField(grid_width, grid_height);	
	public GeomVectorField medicalLayer = new GeomVectorField(grid_width, grid_height);
	public GeomVectorField humanLayer = new GeomVectorField(grid_width, grid_height);
	
	public GeomVectorField networkLayer = new GeomVectorField(grid_width, grid_height);
	public GeomVectorField networkEdgeLayer = new GeomVectorField(grid_width, grid_height);	
	public GeomVectorField majorRoadNodesLayer = new GeomVectorField(grid_width, grid_height);


	/////////////// End Containers ///////////////////////////////////////

	/////////////// Objects //////////////////////////////////////////////

	public Geometry landArea = null;
	public Bag roadNodes = new Bag();
	public Network roads = new Network(false);
	HashMap <MasonGeometry, ArrayList <GeoNode>> localNodes;
	public Bag terminus_points = new Bag();

	public ArrayList <HumanTeleporter> humans = new ArrayList <HumanTeleporter> (2000);
	public Network agentSocialNetwork = new Network();
	
	public GeometryFactory fa = new GeometryFactory();
	
	long mySeed = 0;
	
	Envelope MBR = null;
	
	boolean verbose = false;
	
	public int numDied = 0;
	
	/////////////// END Objects //////////////////////////////////////////

	///////////////////////////////////////////////////////////////////////////
	/////////////////////////// BEGIN functions ///////////////////////////////
	///////////////////////////////////////////////////////////////////////////	
	
	/**
	 * Default constructor function
	 * @param seed
	 */
	public ModernPump(long seed) {
		super(seed);
		random = new MersenneTwisterFast(703356);
	}


	/**
	 * Read in data and set up the simulation
	 */
	public void start()
    {
		super.start();
		try {
			
			GeomVectorField populationLayer = new GeomVectorField(grid_width, grid_height);
			
			//////////////////////////////////////////////
			///////////// READING IN DATA ////////////////
			//////////////////////////////////////////////
		
			readInVectorLayer(baseLayer, dirName + "haiti/haiti_meters.shp", "area", new Bag());
			readInVectorLayer(populationLayer, dirName + "population/popCentroids_meters.shp", "residential areas", new Bag());
			readInVectorLayer(roadLayer, dirName + "roads/roads_meters.shp", "road network", new Bag());
			readInVectorLayer(waterwayLayer, dirName + "waterways/rivers_meters.shp", "waterways", new Bag());
			readInVectorLayer(medicalLayer, dirName + "healthFacilities/health_meters.shp", "health facilities", new Bag());
			
			//////////////////////////////////////////////
			////////////////// CLEANUP ///////////////////
			//////////////////////////////////////////////

			// standardize the MBRs so that the visualization lines up
			
			MBR = roadLayer.getMBR();
//			MBR.init(740000, 780000, 2000000, 2040000); // 35 22  
			MBR.init(740000, 779000, 2009000, 2034000); // 35 22  
//			MBR.init(750000, 772000, 2009500, 2028500); // 22 18  
//			MBR.init(756000, 766000, 2015500, 2022500);
			roadLayer.setMBR(MBR);
			//baseLayer.setMBR(MBR);

			this.grid_width = roadLayer.fieldWidth;
			this.grid_height = roadLayer.fieldHeight;
			
			// base layer
			
			landArea = (Geometry)((MasonGeometry)baseLayer.getGeometries().get(0)).geometry.clone();
			for(Object o: baseLayer.getGeometries()){
				MasonGeometry g = (MasonGeometry) o;
				landArea = landArea.union(g.geometry);
			}
			
			// clean up the road network
			
			System.out.print("Cleaning the road network...");
			
			roads = NetworkUtilities.multipartNetworkCleanup(roadLayer, roadNodes, resolution, fa, random, 0);
			roadNodes = roads.getAllNodes();
			testNetworkForIssues(roads);
			
			// set up roads as being "open" and assemble the list of potential terminii
			roadLayer = new GeomVectorField(grid_width, grid_height);
			for(Object o: roadNodes){
				GeoNode n = (GeoNode) o;
				networkLayer.addGeometry(n);
				
				boolean potential_terminus = false;
				
				// check all roads out of the nodes
				for(Object ed: roads.getEdgesOut(n)){
					
					// set it as being (initially, at least) "open"
					ListEdge edge = (ListEdge) ed;
					((MasonGeometry)edge.info).addStringAttribute("open", "OPEN");
					networkEdgeLayer.addGeometry( (MasonGeometry) edge.info);
					roadLayer.addGeometry((MasonGeometry) edge.info);
					((MasonGeometry)edge.info).addAttribute("ListEdge", edge);
					
					String type = ((MasonGeometry)edge.info).getStringAttribute("highway");
					if(type.equals("motorway") || type.equals("primary") || type.equals("trunk"))
						potential_terminus = true;
				}
				
				// check to see if it's a terminus
				if(potential_terminus && !MBR.contains(n.geometry.getCoordinate()) && roads.getEdges(n, null).size() == 1){
					terminus_points.add(n);
				}

			}

			// reset MBRS in case it got messed up during all the manipulation
			roadLayer.setMBR(MBR);			
			networkLayer.setMBR(MBR);
			networkEdgeLayer.setMBR(MBR);
			waterwayLayer.setMBR(MBR);
			baseLayer.setMBR(MBR);
			medicalLayer.setMBR(MBR);
			humanLayer.setMBR(MBR);

			System.out.println("done");

			/////////////////////
			///////// Clean up roads for Agents to use ///////////
			/////////////////////
						
			Network majorRoads = extractMajorRoads();
			testNetworkForIssues(majorRoads);

			// assemble list of secondary versus local roads
			ArrayList <Edge> myEdges = new ArrayList <Edge> ();
			GeomVectorField secondaryRoadsLayer = new GeomVectorField(grid_width, grid_height);
			GeomVectorField localRoadsLayer = new GeomVectorField(grid_width, grid_height);
			for(Object o: majorRoads.allNodes){
				
				majorRoadNodesLayer.addGeometry((GeoNode)o);
				
				for(Object e: roads.getEdges(o, null)){
					Edge ed = (Edge) e;
					
					myEdges.add(ed);
										
					String type = ((MasonGeometry)ed.getInfo()).getStringAttribute("highway");
					if(type.equals("secondary"))
							secondaryRoadsLayer.addGeometry((MasonGeometry) ed.getInfo());
					else if(type.equals("local"))
							localRoadsLayer.addGeometry((MasonGeometry) ed.getInfo());					
				}
			}

			System.gc();
			
			//////////////////////////////////////////////
			////////////////// AGENTS ///////////////////
			//////////////////////////////////////////////

			// set up the agents in the simulation
			setupAgents(populationLayer);
			humanLayer.setMBR(MBR);
			
/*			// for each of the Agents, set up relevant, environment-specific information
			int aindex = 0;
			for(Human a: humans){
				
				if(a.familiarRoadNetwork == null){
					
					// the Human knows about major roads
					Network familiar = majorRoads.cloneGraph();

					// connect the major network to the Human's location
					connectToMajorNetwork(a.getNode(), familiar);
					
					a.familiarRoadNetwork = familiar;

					// add local roads into the network
					for(Object o: humanLayer.getObjectsWithinDistance(a, 50)){
						Human b = (Human) o;
						if(b == a || b.familiarRoadNetwork != null || b.getNode() != a.getNode()) continue;
						b.familiarRoadNetwork = familiar.cloneGraph();
					}

				}
				
				// connect the Human's work into its personal network
				if(a.getWork() != null)
					connectToMajorNetwork(getClosestGeoNode(a.getWork()), a.familiarRoadNetwork);
				
				// set up its basic paths (fast and quicker and recomputing each time)
				a.setupPaths();

				if(aindex % 100 == 0){ // print report of progress
					System.out.println("..." + aindex + " of " + humans.size());
				}
				aindex++;
			}
*/
//			Disease d = new Disease();
			Cholera d = new Cholera();
			HumanTeleporter h = humans.get(random.nextInt(humans.size()));
			h.acquireDisease(d);
			
			// seed the simulation randomly
//			seedRandom(System.currentTimeMillis());

			// schedule the reporter to run
//			setupReporter();

		} catch (Exception e) { e.printStackTrace();}
    }
	
	/**
	 * Schedule the regular 
	 */
	public void setupReporter() {

		// set up the reporting files
		try {
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * Connect the GeoNode to the given subnetwork using the complete road network
	 * 
	 * @param n - the target node
	 * @param subNetwork - the existing subnetwork
	 */
	void connectToMajorNetwork(GeoNode n, Network subNetwork) {

		try {
			Bag subNetNodes;			
			subNetNodes = (Bag) subNetwork.allNodes.clone();
			
			// find a path using the whole set of roads in the environment 
			AStar pathfinder = new AStar();
			ArrayList <Edge> edges = pathfinder.astarPath(n, new ArrayList <GeoNode> (subNetNodes), roads);
			
			if(edges == null) return; // maybe no such path exists!

			//  otherwise, add the edges into the subnetwork
			for(Edge e: edges){
				GeoNode a = (GeoNode) e.getFrom(), b = (GeoNode) e.getTo();
				if(!subNetwork.nodeExists(a) || !subNetwork.nodeExists(b))
					subNetwork.addEdge(a, b, e.info);
			}

		} catch (CloneNotSupportedException e1) {
			e1.printStackTrace();
		}
	}
	
	/**
	 * Finish the simulation and clean up
	 */
	public void finish(){
		super.finish();
		try{

		} catch (Exception e){
			e.printStackTrace();
		}
	}
	
	/**
	 * Make sure the network doesn't have any problems
	 * 
	 * @param n - the network to be tested
	 */
	static void testNetworkForIssues(Network n){
		System.out.println("testing");
		for(Object o: n.allNodes){
			GeoNode node = (GeoNode) o;
			for(Object p: n.getEdgesOut(node)){
				sim.field.network.Edge e = (sim.field.network.Edge) p;
				LineString ls = (LineString)((MasonGeometry)e.info).geometry;
				Coordinate c1 = ls.getCoordinateN(0);
				Coordinate c2 = ls.getCoordinateN(ls.getNumPoints()-1);
				GeoNode g1 = (GeoNode) e.getFrom();
				GeoNode g2 = (GeoNode) e.getTo();
				if(c1.distance(g1.geometry.getCoordinate()) > 1)
					System.out.println("found you");
				if(c2.distance(g2.geometry.getCoordinate()) > 1)
					System.out.println("found you");
			}
		}
	}
	
	//////////////////////////////////////////////
	////////// UTILITIES /////////////////////////
	//////////////////////////////////////////////

	/**
	 * Method to read in a vector layer
	 * @param layer
	 * @param filename
	 * @param layerDescription
	 * @param attributes - optional: include only the given attributes
	 */
	synchronized void readInVectorLayer(GeomVectorField layer, String filename, String layerDescription, Bag attributes){
		try {
				System.out.print("Reading in " + layerDescription + "from " + filename + "...");
				File file = new File(filename);
				if(attributes == null || attributes.size() == 0)
					ShapeFileImporter.read(file.toURL(), layer);
				else
					ShapeFileImporter.read(file.toURL(), layer, attributes);
				System.out.println("done");	

		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * Method ot read in a raster layer
	 * @param layer
	 * @param filename
	 * @param layerDescription
	 * @param type
	 */
	synchronized void readInRasterLayer(GeomGridField layer, String filename, String layerDescription, GridDataType type){
		try {
				
				System.out.print("Reading in " + layerDescription + "from " + filename + "...");
				FileInputStream fstream = new FileInputStream(filename);
				ArcInfoASCGridImporter.read(fstream, type, layer);
				fstream.close();
				System.out.println("done");

		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * Extract the major roads from the road network
	 * @return a connected network of major roads
	 */
	public Network extractMajorRoads(){
		Network majorRoads = new Network();
		
		// go through all nodes
		for(Object o: roads.getAllNodes()){
		
			GeoNode n = (GeoNode) o;
			
			// go through all edges
			for(Object p: roads.getEdgesOut(n)){
				
				sim.field.network.Edge e = (sim.field.network.Edge) p;
//				String type = ((MasonGeometry)e.info).getStringAttribute("class");
				
				// save major roads
//				if(type.equals("major"))
						majorRoads.addEdge(e.from(), e.to(), e.info);
			}
		}
		
		// merge the major roads into a connected component
		NetworkUtilities.attachUnconnectedComponents(majorRoads, roads);
		
		return majorRoads;
	}
		
	/**
	 * Return the GeoNode in the road network which is closest to the given coordinate
	 * 
	 * @param c
	 * @return
	 */
	public GeoNode getClosestGeoNode(Coordinate c){
		
		// find the set of all nodes within *resolution* of the given point
		Bag objects = networkLayer.getObjectsWithinDistance(fa.createPoint(c), resolution);
		if(objects == null || networkLayer.getGeometries().size() <= 0) 
			return null; // problem with the network layer

		// among these options, pick the best
		double bestDist = resolution; // MUST be within resolution to count
		GeoNode best = null;
		for(Object o: objects){
			double dist = ((GeoNode)o).geometry.getCoordinate().distance(c);
			if(dist < bestDist){
				bestDist = dist;
				best = ((GeoNode)o);
			}
		}
		
		// if there is a best option, return that!
		if(best != null && bestDist == 0) 
			return best;
		
		// otherwise, closest GeoNode is associated with the closest Edge, so look for that!
		
		ListEdge edge = getClosestEdge(c);
		
		// find that edge
		if(edge == null){
			edge = getClosestEdge(c, resolution * 10);
			if(edge == null)
				return null;
		}
		
		// of that edge's endpoints, find the closer of the two and return it
		GeoNode n1 = (GeoNode) edge.getFrom();
		GeoNode n2 = (GeoNode) edge.getTo();
		
		if(n1.geometry.getCoordinate().distance(c) <= n2.geometry.getCoordinate().distance(c))
			return n1;
		else 
			return n2;
	}
	
	/**
	 * Return the ListEdge in the road network which is closest to the given coordinate
	 * 
	 * @param c
	 * @return
	 */
	public ListEdge getClosestEdge(Coordinate c){
		
		// find the set of all edges within *resolution* of the given point
		Bag objects = networkEdgeLayer.getObjectsWithinDistance(fa.createPoint(c), resolution);
		if(objects == null || networkEdgeLayer.getGeometries().size() <= 0) 
			return null; // problem with the network edge layer
		
		Point point = fa.createPoint(c);
		
		// find the closest edge among the set of edges
		double bestDist = resolution;
		ListEdge bestEdge = null;
		for(Object o: objects){
			double dist = ((MasonGeometry)o).getGeometry().distance(point);
			if(dist < bestDist){
				bestDist = dist;
				bestEdge = (ListEdge) ((AttributeValue) ((MasonGeometry) o).getAttribute("ListEdge")).getValue();
			}
		}
		
		// if it exists, return it
		if(bestEdge != null)
			return bestEdge;
		
		// otherwise return failure
		else
			return null;
	}
	
	/**
	 * Return the ListEdge in the road network which is closest to the given coordinate, within the given resolution
	 * 
	 * @param c
	 * @param resolution
	 * @return
	 */
	public ListEdge getClosestEdge(Coordinate c, double resolution){

		// find the set of all edges within *resolution* of the given point
		Bag objects = networkEdgeLayer.getObjectsWithinDistance(fa.createPoint(c), resolution);
		if(objects == null || networkEdgeLayer.getGeometries().size() <= 0) 
			return null; // problem with the network edge layer
		
		Point point = fa.createPoint(c);
		
		// find the closest edge among the set of edges
		double bestDist = resolution;
		ListEdge bestEdge = null;
		for(Object o: objects){
			double dist = ((MasonGeometry)o).getGeometry().distance(point);
			if(dist < bestDist){
				bestDist = dist;
				bestEdge = (ListEdge) ((AttributeValue) ((MasonGeometry) o).getAttribute("ListEdge")).getValue();
			}
		}
		
		// if it exists, return it
		if(bestEdge != null)
			return bestEdge;
		
		// otherwise return failure
		else
			return null;
	}
	
	/** set the seed of the random number generator */
	void seedRandom(long number){
		random = new MersenneTwisterFast(number);
		mySeed = number;
	}
	
	public void setupAgents(GeomVectorField populationLayer){
		Bag nodeBag = majorRoadNodesLayer.getGeometries();
		
		int numNodes = nodeBag.size();
		for (Object o : populationLayer.getGeometries()) {
			MasonGeometry g = (MasonGeometry) o;
			Coordinate c = g.geometry.getCoordinate();
			for (int i = 0; i < 10; i++) {
//				GeoNode gn = (GeoNode) nodeBag.get(random.nextInt(numNodes));
//				Coordinate myHome = (Coordinate) gn.geometry.getCoordinate().clone();
//				double distance = Math.abs(random.nextGaussian()) * 1500;
//				double degrees = random.nextDouble() * 2 * Math.PI;
//				double xOffset = distance * Math.cos(degrees) + c.x;
				double xOffset = random.nextGaussian() * 1000 + c.x;
//				double yOffset = distance * Math.sin(degrees) + c.y;
				double yOffset = random.nextGaussian() * 1000 + c.y;
				Coordinate myHome = new Coordinate(xOffset, yOffset);
				Geometry point = fa.createPoint(myHome);
				if(!landArea.contains(point)) continue;
				HumanTeleporter hum = new HumanTeleporter("id_" + random.nextLong(), myHome, myHome, this);
				humanLayer.addGeometry(hum);
				humans.add(hum);
			}
		}
	}
	
	// reset the agent layer's MBR
	public void resetLayers(){
		MBR = roadLayer.getMBR();
//		MBR.init(740000, 780000, 2000000, 2040000); // 35 22  
		MBR.init(740000, 779000, 2009000, 2034000); // 35 22  
//		MBR.init(745000, 775000, 2015000, 2030000);
		this.humanLayer.setMBR(MBR);
		//this.baseLayer.setMBR(MBR);
	}
	
	/**
	 * To run the model without visualization
	 */
/*	public static void main(String[] args)
    {
		
		if(args.length < 8){
			System.out.println("usage error");
			System.exit(0);
		}
		
		ModernPump myPump = new ModernPump(System.currentTimeMillis());
		
		myPump.decayParam = Double.parseDouble(args[6]);
		myPump.speed = Double.parseDouble(args[7]);
		
		System.out.println("Loading...");

		myPump.start();

		System.out.println("Running...");

		for(int i = 0; i < 288 * 3; i++){
			myPump.schedule.step(myPump);
		}
		
		myPump.finish();
		
		System.out.println("...run finished");

		System.exit(0);
    }
    */
}