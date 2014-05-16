package modernPump.agents;

import modernPump.sim.ModernPump;
import sim.engine.Schedule;
import sim.engine.SimState;
import sim.engine.Steppable;
import sim.engine.Stoppable;
import sim.util.Bag;

public class Disease implements Steppable {
	
	DiseaseVector host;
	String name = "flu";
	Stoppable stopper;
	
	public Disease(){
	}
	
	public void start(Schedule schedule){
		schedule.scheduleRepeating(new Steppable() {

			@Override
			public void step(SimState arg0) {
				
				// spread infection
				
				ModernPump world = (ModernPump) arg0;
				Bag exposed = world.humanLayer.getObjectsWithinDistance(host.getGeometry(), transmissableRadius(host, null));
				if (exposed.size() <= 1)
					return;
				for (Object o : exposed) {
					if (!((Human) o).infectedWith(name) && world.random.nextDouble() < transmissability(host, (DiseaseVector) o))
						((Human) o).acquireDisease(copy());
				}
				
				// TODO simulate progression of disease itself
			}

		});

	}
	
	public double virulence(DiseaseVector host, DiseaseVector target){
		return 0;
	}
	
	public double transmissability(DiseaseVector host, DiseaseVector target){
		return .3;
	}
	
	public double transmissableRadius(DiseaseVector host, DiseaseVector target){
		return 30;
	}
	
	public double incubationPeriod(DiseaseVector host, DiseaseVector target){
		return 0;
	}

	public Disease copy(){
		return new Disease();
		// TODO: genetic variaaaation!
	}
	
	@Override
	public void step(SimState arg0) {
		
	}
	
	public String getName(){
		return name;
	}
	
	public void setHost(DiseaseVector v){
		host = v;
	}
}