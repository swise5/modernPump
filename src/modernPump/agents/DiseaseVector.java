package modernPump.agents;

import modernPump.agents.diseases.Disease;

import com.vividsolutions.jts.geom.Geometry;

public interface DiseaseVector {
	
	public void acquireDisease(Disease d);
	
	public void loseDisease(Disease d);
	
	public Geometry getGeometry();
	
	public boolean stillExists();
	
	public void changeStage(int stage);
}