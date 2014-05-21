package modernPump.viz;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

import javax.swing.JFrame;

import modernPump.sim.ModernPump;

import org.jfree.data.xy.XYSeries;

import ec.util.MersenneTwisterFast;
import sim.display.Console;
import sim.display.Controller;
import sim.display.Display2D;
import sim.display.GUIState;
import sim.engine.SimState;
import sim.engine.Steppable;
import sim.portrayal.geo.GeomPortrayal;
import sim.portrayal.geo.GeomVectorFieldPortrayal;
import sim.portrayal.grid.FastValueGridPortrayal2D;
import sim.portrayal.DrawInfo2D;
import sim.portrayal.FieldPortrayal2D;
import sim.util.gui.SimpleColorMap;
import sim.util.media.chart.TimeSeriesChartGenerator;
import swise.disasters.Wildfire;
import swise.visualization.AttributePolyPortrayal;
import swise.visualization.FilledPolyPortrayal;
import swise.visualization.GeomNetworkFieldPortrayal;

/**
 * A visualization of the ModernPump simulation.
 * 
 * @author swise
 */
public class ModernPumpWithUI extends GUIState {

	ModernPump sim;
	public Display2D display;
	public JFrame displayFrame;
	
	// Map visualization objects
	private GeomVectorFieldPortrayal map = new GeomVectorFieldPortrayal();
	private GeomVectorFieldPortrayal roads = new GeomVectorFieldPortrayal();
	private GeomVectorFieldPortrayal water = new GeomVectorFieldPortrayal();
	private GeomVectorFieldPortrayal homes = new GeomVectorFieldPortrayal();
	private GeomVectorFieldPortrayal humans = new GeomVectorFieldPortrayal();
	private GeomVectorFieldPortrayal diseases = new GeomVectorFieldPortrayal();
	private GeomVectorFieldPortrayal health = new GeomVectorFieldPortrayal();

//	private FastValueGridPortrayal2D heatmap = new FastValueGridPortrayal2D();	
		
	///////////////////////////////////////////////////////////////////////////
	/////////////////////////// BEGIN functions ///////////////////////////////
	///////////////////////////////////////////////////////////////////////////	
	
	/** Default constructor */
	public ModernPumpWithUI(SimState state) {
		super(state);
		sim = (ModernPump) state;
	}

	/** Begins the simulation */
	public void start() {
		super.start();
		
		// set up portrayals
		setupPortrayals();
	}

	/** Loads the simulation from a point */
	public void load(SimState state) {
		super.load(state);
		
		// we now have new grids. Set up the portrayals to reflect that
		setupPortrayals();
	}

	/**
	 * Sets up the portrayals of objects within the map visualization. This is called by both start() and by load()
	 */
	public void setupPortrayals() {
		
		ModernPump world = (ModernPump) state;
		map.setField(world.baseLayer);
		map.setPortrayalForAll(new GeomPortrayal(new Color(241,244,199), true));
//		map.setPortrayalForAll(new GeomPortrayal(new Color(30,30,30), true));
		map.setImmutableField(true);
		
		roads.setField(world.roadLayer);
		roads.setPortrayalForAll(new GeomPortrayal(new Color(249, 203, 124), 4));
		roads.setImmutableField(true);		

		water.setField(world.waterwayLayer);
//		water.setPortrayalForAll(new GeomPortrayal(Color.blue, false));
		water.setPortrayalForAll(new GeomPortrayal(new Color(33, 112, 181), false));

		health.setField(world.medicalLayer);
		health.setPortrayalForAll(new FilledPolyPortrayal(Color.green, Color.black, 100, true));
		health.setImmutableField(true);
		
/*		humans.setField(world.humanLayer);
		humans.setPortrayalForAll( new AttributePolyPortrayal(
						new SimpleColorMap(new Color[]{new Color(146,147,121, 40),//new Color(50,50,50,10), 
								Color.red, Color.red, new Color(100,100,100,30)}),//new SimpleColorMap(0,1, new Color(255,255,0,100), new Color(255,0,0,150)),
						"Sick", new Color(0,0,0,0), true, 50));
	*/
		humans.setField(world.humanLayer);
		humans.setPortrayalForAll( new GeomPortrayal(new Color(146,147,121), 50, true));
				
		diseases.setField(world.diseasesLayer);
		diseases.setPortrayalForAll(new GeomPortrayal(Color.red, 50));
		
		homes.setField(world.homesLayer);
		homes.setPortrayalForAll( new GeomPortrayal(new Color(146,147,121, 40), 50, true));
		homes.setImmutableField(true);
		
//		heatmap.setField(world.heatmap.getGrid()); 
//		heatmap.setMap(new SimpleColorMap(0, 10, Color.black, Color.red));
		
		// reset stuff
		// reschedule the displayer
		display.reset();
		display.setBackdrop(new Color(195, 225, 248));
//		display.setBackdrop(Color.black);

		// redraw the display
		display.repaint();
	}

	/** Initializes the simulation visualization */
	public void init(Controller c) {
		super.init(c);

		// the map visualization
		display = new Display2D((int)(1.5 * sim.grid_width), (int)(1.5 * sim.grid_height), this);

//		display.attach(heatmap, "Heatmap", false);
		display.attach(map, "Landscape");
//		display.attach(homes, "Baseline Population");
		display.attach(humans, "Agents");
		display.attach(diseases, "Diseases");
		display.attach(water, "Waterways");
		display.attach(roads, "Roads");
//		display.attach(health, "Medical Centers");
		
		
		// ---TIMESTAMP---
		display.attach(new FieldPortrayal2D()
	    {
			private static final long serialVersionUID = 1L;
			
			Font font = new Font("SansSerif", 0, 24);  // keep it around for efficiency
		    SimpleDateFormat ft = new SimpleDateFormat ("yyyy-MM-dd HH:mm zzz");
		    public void draw(Object object, Graphics2D graphics, DrawInfo2D info)
		        {
		        String s = "";
		        if (state !=null) // if simulation has not begun or has finished, indicate this
		            s = state.schedule.getTimestamp("Before Simulation", "Simulation Finished");
		        graphics.setColor(Color.white);
		        if (state != null){
		        	// specify the timestep here
		        	Date startDate;
					try {
						startDate = ft.parse("2012-06-21 00:00 MST");
				        Date time = new Date((int)state.schedule.getTime() * 300000 + startDate.getTime());
				        s = ft.format(time);	
					} catch (ParseException e) {
						e.printStackTrace();
					}
		        }

		        graphics.drawString(s, (int)(info.clip.x + info.clip.width - font.getStringBounds(s, graphics.getFontRenderContext()).getWidth()), 
		                (int)(info.clip.y + info.clip.height - 10 - font.getStringBounds(s,graphics.getFontRenderContext()).getHeight()));

		        }
		    }, "Time");
		
		displayFrame = display.createFrame();
		c.registerFrame(displayFrame); // register the frame so it appears in the "Display" list
		displayFrame.setVisible(true);
	}

	/** Quits the simulation and cleans up.*/
	public void quit() {
		super.quit();

		if (displayFrame != null)
			displayFrame.dispose();
		displayFrame = null; // let gc
		display = null; // let gc
	}

	/** Runs the simulation */
	public static void main(String[] args) {
		ModernPumpWithUI gui =  null;
		
		try {
			ModernPump lb = new ModernPump(703356);//System.currentTimeMillis());
			gui = new ModernPumpWithUI(lb);
		} catch (Exception ex){
			System.out.println(ex.getStackTrace());
		}
		
		Console console = new Console(gui);
		console.setVisible(true);
	}

	/** Returns the name of the simulation */
	public static String getName() { return "ModernPump"; }

	/** Allows for users to modify the simulation using the model tab */
	public Object getSimulationInspectedObject() { return state; }

}