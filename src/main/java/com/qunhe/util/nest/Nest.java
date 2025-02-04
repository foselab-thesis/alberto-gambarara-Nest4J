package com.qunhe.util.nest;

import static com.qunhe.util.nest.util.IOUtils.debug;
import static com.qunhe.util.nest.util.IOUtils.log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Observer;

import org.uncommons.watchmaker.framework.PopulationData;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.qunhe.util.nest.algorithm.GeneticAlgorithm;
import com.qunhe.util.nest.algorithm.Individual;
import com.qunhe.util.nest.config.Config;
import com.qunhe.util.nest.contest.InputConfig;
import com.qunhe.util.nest.data.Bound;
import com.qunhe.util.nest.data.NestPath;
import com.qunhe.util.nest.data.NfpKey;
import com.qunhe.util.nest.data.NfpPair;
import com.qunhe.util.nest.data.ParallelData;
import com.qunhe.util.nest.data.Placement;
import com.qunhe.util.nest.data.Result;
import com.qunhe.util.nest.data.Segment;
import com.qunhe.util.nest.data.PathPlacement;
import com.qunhe.util.nest.util.CommonUtil;
import com.qunhe.util.nest.util.GeometryUtil;
import com.qunhe.util.nest.util.IOUtils;
import com.qunhe.util.nest.util.NfpUtil;
import com.qunhe.util.nest.util.Placementworker;

/**
 * @author yisa
 */
public class Nest {

    private  NestPath binPath;
    private  List<NestPath> parts;
    private Config config;
    private int loopCount;
    private GeneticAlgorithm GA = null ;
    private Map<String,List<NestPath>> nfpCache;			// NoFitPolygon Cache
    private static Gson gson = new GsonBuilder().create();
    private int launchcount =0;


	/**
	 * Create a new Nest object
	 * @param binPath base polygon
	 * @param parts   list of plate polygons
	 * @param config  parameter settings
	 * @param count   The number of iterations to calculate
	 */
	public Nest(NestPath binPath, List<NestPath> parts, Config config, int count) {
		this.binPath = binPath;
		this.parts = parts;
		this.config = config;
		this.loopCount = count;
		nfpCache = new HashMap<>();
	}

    /**
     * Start the Nest calculation 
     * @return the placements' list of all NestPath
     */
    public  List<List<Placement>> startNest(){


    	/*---------------------------------------------------PRELIMINARY SETTINGS----------------------------------------------------------------*/
        
    	List<NestPath> tree = CommonUtil.BuildTree(parts , Config.CURVE_TOLERANCE);		//conversione di eventuali self-intersecting polygons in pologoni semplici
        CommonUtil.offsetTree(tree, 0.5 * config.SPACING);
        binPath.config = config;
        for(NestPath nestPath: parts){
            nestPath.config = config;
        }
        
        NestPath binPolygon = CommonUtil.cleanNestPath(binPath);	//conversione di un eventuale binPath self intersecting in un poligono semplice
        // Bound binBound = GeometryUtil.getPolygonBounds(binPolygon);
        if(Config.BOUND_SPACING > 0 ){
            List<NestPath> offsetBin = CommonUtil.polygonOffset(binPolygon , - Config.BOUND_SPACING);
            if(offsetBin.size() == 1 ){
                binPolygon = offsetBin.get(0);
            }
        }
        binPolygon.setId(-1);
        // A part may become not positionable after a rotation. TODO this can also be removed if we know that all parts are legal
        if(!Config.ASSUME_ALL_PARTS_PLACABLE) {
            List<Integer> integers = checkIfCanBePlaced(binPolygon, tree);
            List<NestPath> safeTree = new ArrayList<>();
            for (Integer i : integers) {
                safeTree.add(tree.get(i));
            }
            tree = safeTree;
        }
        
        /*VENGONO SETTATE LE COORDINATE MAX E MIN DEL SINGOLO BINPATH PER POI POTERLO TRASLARE NELL'ORIGINE*/
        double xbinmax = binPolygon.get(0).x;	// get.(0) = prende il primo segmento dei 4 (coordinate del primo vertice), se si assume che la superficie sia rettangolare
        double xbinmin = binPolygon.get(0).x;
        double ybinmax = binPolygon.get(0).y;
        double ybinmin = binPolygon.get(0).y;
        // Find min max
        for(int i = 1 ; i<binPolygon.size(); i++){
            if(binPolygon.get(i).x > xbinmax ){
                xbinmax = binPolygon.get(i).x;
            }
            else if (binPolygon.get(i).x < xbinmin ){
                xbinmin = binPolygon.get(i) .x;
            }

            if(binPolygon.get(i).y > ybinmax ){
                ybinmax = binPolygon.get(i).y;
            }
            else if (binPolygon.get(i). y <ybinmin ){
                ybinmin = binPolygon.get(i).y;
            }
        }
 
        /*VIENE TRASLATO IL POLIGONO BINPATH NELL'ORIGINE*/
        for(int i=0; i<binPolygon.size(); i++){				
            binPolygon.get(i).x -= xbinmin;
            binPolygon.get(i).y -= ybinmin;
        }

        //double binPolygonWidth = xbinmax - xbinmin;
        //double binPolygonHeight = ybinmax - ybinmin;
        
        /*VIENE ROVESCIATO IL POLIGONO BINPATH NELL'ORIGINE PER AVERE L'ORIGINE NON IN ALTO A SX MA IN BASSO A SX*/
        if(GeometryUtil.polygonArea(binPolygon) > 0 ){
            binPolygon.reverse();
        }
        /**
         * Make sure it's counterclockwise (rotazione antioraria) TODO why?
         */
        for (NestPath element : tree) {
            Segment start = element.get(0);
            Segment end = element.get(element.size()-1);
            if(start == end || GeometryUtil.almostEqual(start.x , end.x) && GeometryUtil.almostEqual(start.y , end.y)){
                element.pop();
            }
            if(GeometryUtil.polygonArea(element) > 0 ){
                element.reverse();
            }
        }

        
        /*---------------------------------------------------START NESTING----------------------------------------------------------------*/
        launchcount = 0;
        Result best = null;
        List<List<Placement>> appliedPlacement = null;
        
        for(int i = 0 ; i<loopCount ; i++) {
            Result result = launchWorkers(tree, binPolygon, config);
            if(best!=null)log(best.fitness);
            // se result ottiene valore minore della fitness, sarÃ  il nuovo vaolre di best fitness
            if(i == 0 ||  best.fitness > result.fitness){
                best = result;
                double rate = computeUseRate(best, tree);
                //log("Loop "+i+" width = "+best.fitness+"; use rate = " + rate);
                appliedPlacement = applyPlacement(best,tree);
                try {
                	String lotId = InputConfig.INPUT == null ? "" : InputConfig.INPUT.get(0).lotId;
                    String file = Config.OUTPUT_DIR + lotId + "_" +i +"_" +
                        (int)(rate*10) + ".csv";
                    debug("Save to file "+file);
                    IOUtils.saveToMultiFile(file, appliedPlacement, InputConfig.INPUT_POLY);
                }catch (Exception e){
                    log(e);
                }
                
                //
                // comunica agli observer che il result 
                // spostato dentro l'if
                notifyObserver(appliedPlacement);
                //                
            }
            notifyObserver(result);
        }
        //appliedPlacement = applyPlacement(best,tree);
        return appliedPlacement;
    }

	// observable /observe pattern
	public interface ListPlacementObserver {
		void populationUpdate(List<List<Placement>> appliedPlacement);
	}

	public interface ResultObserver {
		void muationStepDone(Result result);
	}

	public List<ListPlacementObserver> observers = new ArrayList<>();

	public List<ResultObserver> resultobservers = new ArrayList<>();

	public void notifyObserver(List<List<Placement>> appliedPlacement) {
		for (ListPlacementObserver lo : observers) {
			lo.populationUpdate(appliedPlacement);
		}
	}

	public void notifyObserver(Result result) {
		for (ResultObserver lo : resultobservers) {
			lo.muationStepDone(result);
		}
	}

	public double computeUseRate(Result best, List<NestPath> tree) {
		// Log new result
		double sumarea = 0;
		double totalarea = Config.BIN_HEIGHT * best.fitness;
		for (List<PathPlacement> element : best.placements) {
			// totalarea += Math.abs(GeometryUtil.polygonArea(binPolygon));
			for (PathPlacement element2 : element) {
				sumarea += Math.abs(GeometryUtil.polygonArea(tree.get(element2.id)));
			}
		}
		return (sumarea / totalarea) * 100;
	}

	/**
	 *Applicazione dell'algoritmo di nesting
   * @param tree  lista di tutti i poligoni da disporre
   * @param binPolygon    superficie principale su cui disporre i poligoni
   * @param config   confiugurazione standard
   * @return	bestResult 
	 */
	public Result launchWorkers(List<NestPath> tree, NestPath binPolygon, Config config) {
		launchcount++;
		if (Config.IS_DEBUG) {
			log("launchWorkers(): launching worker " + launchcount);
		}
		// GA is null by default
		if (GA == null) {
			// A clone of tree is created
			List<NestPath> adam = new ArrayList<>();
			for (NestPath nestPath : tree) {
				NestPath clone = new NestPath(nestPath);
				adam.add(clone);
			}
			for (NestPath nestPath : adam) {
				nestPath.area = GeometryUtil.polygonArea(nestPath);
			}
			Collections.sort(adam);
			GA = new GeneticAlgorithm(adam, binPolygon, config);
		}

		Individual individual = null;
		for (Individual element : GA.population) {
			if (element.getFitness() < 0) {
				individual = element;
				break;
			}
		}
    //        if(individual == null ){
    //            GA.generation();
    //            individual = GA.population.get(1);
    //        }
               
        /*---------------------GENERATION OF CHILDERN---------------------*/
        // dalla seconda iterazione di loopcount nel metodo startNest --> launchcount >= 2
        if(launchcount > 1 && individual == null ){
            GA.generation();
            individual = GA.population.get(1);
        }
        if(Config.IS_DEBUG) {
            log("launchWorkers(): GA: individual ready.");
        }


        //Above is GA. Now we got a set of candidates
        List<NestPath> placelist = individual.getPlacement();
        List<Integer> rotations = individual.getRotation();

        // polygons are being set the ids
        List<Integer> ids = new ArrayList<>();	
        for(int i = 0 ; i < placelist.size(); i ++){
            ids.add(placelist.get(i).getId());
            placelist.get(i).setRotation(rotations.get(i));
        }
        
        /*-------------------------------------CREATE NFP CACHE-------------------------------------*/
        if(Config.NFP_CACHE_PATH != null){
            debug("Loading nfp from file "+Config.NFP_CACHE_PATH);
            nfpCache = IOUtils.loadNfpCache(Config.NFP_CACHE_PATH);
        }
        List<NfpPair> nfpPairs = new ArrayList<>();
        NfpKey key = null;
        //If nfpKey is not found in nfpCache, add it to nfpPairs.
        for(int i = 0 ; i< placelist.size();i++){
            NestPath part = placelist.get(i);
            key = new NfpKey(binPolygon .getId() , part.getId() , true , 0 , part.getRotation());
            // ATTENZIONE sara'Â  sempre false
            if(!nfpCache.containsKey(key)) {
                nfpPairs.add(new NfpPair(binPolygon, part, key));
            }
            for(int j = 0 ; j< i ; j ++){
                NestPath placed = placelist.get(j);
                NfpKey keyed = new NfpKey(placed.getId() , part.getId() , false , rotations.get(j), rotations.get(i));
                // ATTENZIONE sarÃÂ  sempre false
                if(!nfpCache.containsKey(keyed)) {
                    nfpPairs.add(new NfpPair(placed, part, keyed));
                }
            }
        }

		if (Config.IS_DEBUG) {
			log("launchWorkers(): Generating nfp...nb of nfp pairs = " + nfpPairs.size());
		}

		// The first time nfpCache is empty, nfpCache stores Nfp ( List<NestPath> ) formed by two polygons corresponding to nfpKey
		
		List<ParallelData> generatedNfp = new ArrayList<>();
		int cnt = 0;
		for (NfpPair nfpPair : nfpPairs) {
			if (++cnt % 1000 == 0) {
				// debug(" nfp generated.");
				debug("Generating nfp " + cnt + ": " + nfpPair.getA().getBid() + "," + nfpPair.getB().getBid());
			}
			ParallelData data = NfpUtil.nfpGenerator(nfpPair, config);
			if (data == null) {
				debug("Null nfp " + cnt + ": " + nfpPair.getA().getBid() + "," + nfpPair.getB().getBid());
			}
			generatedNfp.add(data);
		}
		for (ParallelData Nfp : generatedNfp) {
			// TODO remove gson & generate a new key algorithm
			String tkey = gson.toJson(Nfp.getKey());
			nfpCache.put(tkey, Nfp.value);
		}
		//log("Saving nfpCache.");
		String lotId = InputConfig.INPUT == null ? "" : InputConfig.INPUT.get(0).lotId;
		IOUtils.saveNfpCache(nfpCache, Config.OUTPUT_DIR + "nfp" + lotId + ".txt");

		
		/*---------------------FITNESS COMPUTATION---------------------*/
		
		debug("Launching placement worker...");
		// Places parts according to the sequence specified by the individual
		Placementworker worker = new Placementworker(binPolygon, config, nfpCache); // --------> uses Placementworker to set fitness value
																					
		List<NestPath> placeListSlice = new ArrayList<>();

		for (int i = 0; i < placelist.size(); i++) {
			placeListSlice.add(new NestPath(placelist.get(i)));
		}
		// Some simplification:
		// List<List<NestPath>> data = new ArrayList<List<NestPath>>();
		// data.add(placeListSlice);
		List<Result> results = new ArrayList<>();
		// for(int i = 0 ;i <data.size() ; i++){
		Result result = worker.placePaths(placeListSlice);// data.get(i)
		results.add(result);
		// }
		if (results.size() == 0) {
			return null;
		}
		individual.setFitness(results.get(0).fitness);
		Result bestResult = results.get(0);
		for (int i = 1; i < results.size(); i++) {
			if (results.get(i).fitness < bestResult.fitness) {
				bestResult = results.get(i); // Fitness is the lowest value
			}
		}
		debug("launchWorkers(): current best fitness = " + bestResult.fitness);
		return bestResult;
	}

	/**
	 * Bind translate and rotate to the corresponding board by id and bid
	 *
	 * @param best  current fitness best value
	 * @param tree  all NestPaths
	 * @return applyPlacement 	all the translations/rotations that have been done on the best current Individual
	 */
    public static List<List<Placement>> applyPlacement(Result best , List<NestPath> tree){
        List<List<Placement>> applyPlacement = new ArrayList<>();
        for(int i = 0; i<best.placements.size();i++){
            List<Placement> binTranslate = new ArrayList<>();
            for(int j = 0 ; j <best.placements.get(i).size(); j ++){
                PathPlacement v = best.placements.get(i).get(j);
                NestPath nestPath = tree.get(v.id);
                for(NestPath child : nestPath.getChildren()){
                    Placement chPlacement = new Placement(child.getBid() , new Segment(v.x,v.y) , v.rotation);
                    binTranslate.add(chPlacement);
                }
                Placement placement = new Placement(nestPath.getBid() , new Segment(v.x,v.y) , v.rotation);
                binTranslate.add(placement);
            }
            applyPlacement.add(binTranslate);
        }
        return applyPlacement;
    }


	/**
	 ** Every time a new population is generated by mutation or mating in the genetic algorithm,
	 *the result that the plate and the rotation angle do not match may occur, and it needs to be re-checked and adapted.
	 *
	 * @param binPolygon
	 * @param tree
	 * @return
	 */
	public static List<Integer> checkIfCanBePlaced(NestPath binPolygon, List<NestPath> tree) {
		List<Integer> CanBePlacdPolygonIndex = new ArrayList<>();
		Bound binBound = GeometryUtil.getPolygonBounds(binPolygon);
		for (int i = 0; i < tree.size(); i++) {
			NestPath nestPath = tree.get(i);
			if (nestPath.getRotation() == 0) {
				Bound bound = GeometryUtil.getPolygonBounds(nestPath);
				if (bound.width < binBound.width && bound.height < binBound.height) {
					CanBePlacdPolygonIndex.add(i);
					continue;
				}
			} else {
				for (int j = 0; j < nestPath.getRotation(); j++) {
					Bound rotatedBound = GeometryUtil.rotatePolygon(nestPath, (360 / nestPath.getRotation()) * j);
					if (rotatedBound.width < binBound.width && rotatedBound.height < binBound.height) {
						CanBePlacdPolygonIndex.add(i);
						break;
					}
				}
			}
		}
		return CanBePlacdPolygonIndex;
	}

	public void add(NestPath np) {
		parts.add(np);
	}

	public NestPath getBinPath() {
		return binPath;
	}

	public List<NestPath> getParts() {
		return parts;
	}

	public void setBinPath(NestPath binPath) {
		this.binPath = binPath;
	}

	public void setParts(List<NestPath> parts) {
		this.parts = parts;
	}

	public Config getConfig() {
		return config;
	}

	public void setConfig(Config config) {
		this.config = config;
	}

}
