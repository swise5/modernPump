package modernPump.agents.diseases;

import modernPump.agents.DiseaseVector;
import modernPump.agents.HumanTeleporter;
import modernPump.sim.ModernPump;
import sim.engine.Schedule;
import sim.engine.SimState;
import sim.engine.Steppable;
import sim.util.Bag;

public class Cholera extends Disease {
	
	int stage = 0;
	int timeInStage = 0;
	
	int incubationPeriod = 100;//334; // ~1.5 days if timestep = 5 mins
	int durationPeriod = 288;
	
	public static int stage_INCUBATING = 1;
	public static int stage_ACUTE = 2;
	public static int stage_RECOVERED = 3;
	
	public Cholera(){
		name = "Cholera";
	}


	@Override
	public void step(SimState state) {
		
		// if the host has disappeared, Cholera can no longer be transmitted TODO verify
		if(!host.stillExists()){
			return;
		}
		
		if(timeInStage > durationPeriod && host.stillExists()){
			stage = stage_RECOVERED;
			host.changeStage(stage);
			host.loseDisease(this);
		}
		else if(stage == stage_ACUTE){
			ModernPump world = (ModernPump) state;
			Bag exposed = world.humanLayer.getObjectsWithinDistance(host.getGeometry(), transmissableRadius(host, null));
			if (exposed.size() <= 1){
				state.schedule.scheduleOnce(state.schedule.getTime() + 1, this);
				return;
			}
			for (Object o : exposed) {
				if (!((HumanTeleporter) o).infectedWith(name) && world.random.nextDouble() < transmissability(host, (DiseaseVector) o))
					((HumanTeleporter) o).acquireDisease(copy());
			}
			
			timeInStage++;
			state.schedule.scheduleOnce(state.schedule.getTime() + 1, this);
		}
		
		else if(stage == stage_INCUBATING){
			
			ModernPump world = (ModernPump) state;
			Bag exposed = world.humanLayer.getObjectsWithinDistance(host.getGeometry(), transmissableRadius(host, null));
			if (exposed.size() <= 1){
				state.schedule.scheduleOnce(state.schedule.getTime() + 1, this);
				return;
			}
			for (Object o : exposed) {
				if (!((HumanTeleporter) o).infectedWith(name) && world.random.nextDouble() < transmissability(host, (DiseaseVector) o))
					((HumanTeleporter) o).acquireDisease(copy());
			}

			timeInStage++;
			if(timeInStage > incubationPeriod){
				stage = stage_ACUTE;
				host.changeStage(stage);
				timeInStage = 0;
			}
			state.schedule.scheduleOnce(state.schedule.getTime() + 1, this);
		}
	}

	public void start(Schedule schedule){
		stage = stage_INCUBATING;
		host.changeStage(stage);
		schedule.scheduleOnce(schedule.getTime() + 1, this);
	}
	
	public Disease copy(){
		return new Cholera();
		// TODO: genetic variaaaation!
	}

	@Override
	public double transmissableRadius(DiseaseVector host, DiseaseVector target){
		return 200;
	}
	
	@Override
	public double transmissability(DiseaseVector host, DiseaseVector target){
		return .05;
	}
	
}