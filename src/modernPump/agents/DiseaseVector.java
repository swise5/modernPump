package modernPump.agents;

import com.vividsolutions.jts.geom.Geometry;

interface DiseaseVector {
	
	public void acquireDisease(Disease d);
	
	public void loseDisease(Disease d);
	
	public Geometry getGeometry();
}