package modernPump.sim;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.GeometryFactory;

import sim.engine.SimState;
import sim.engine.Steppable;
import sim.field.geo.GeomVectorField;
import sim.io.geo.ShapeFileImporter;
import sim.util.geo.MasonGeometry;

public class Test extends SimState {

	private static final long serialVersionUID = 1L;
	
	String dataDir = "someDataDir";
	String fileType = ".whatever";

	public int grid_width =	600;
	public int grid_height = 450;

	public Date startTime = null;

	GeomVectorField baseLayer = new GeomVectorField(grid_width, grid_height);
	GeomVectorField objectLayer = new GeomVectorField(grid_width, grid_height);

	Envelope MBR = null;

	public Test(long seed) {
		super(seed);
	}

	public void start() {
		super.start();
		try {
			
			File file = new File("someFilename");
			ShapeFileImporter.read(file.toURL(), baseLayer);

			MBR = baseLayer.getMBR();
			
			// Resize the boundaries of the simulation according to preference
			MBR.init(-125, -65, 25, 50); // USA

			objectLayer.setMBR(MBR);

			// --- DATA INPUT ---

			// set up the structures to open and process the data
			
			File folder = new File(dataDir);

		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public static void main(String[] args)
    {
		doLoop(Test.class, args);
		System.exit(0);
    }

}