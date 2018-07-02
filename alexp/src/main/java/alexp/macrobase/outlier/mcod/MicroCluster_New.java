/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package alexp.macrobase.outlier.mcod;

import alexp.macrobase.outlier.mcod.mtree.MTreeClass;
import java.util.*;

/**
 * @author Luan
 */
public class MicroCluster_New {
    
    private double maxDistance = 1; // R in paper
    private int minNeighborCount = 30; // k in paper
    private int windowSize = 1000; // W in paper
    private int slide = 500;

    private HashMap<Integer, MCO> dataList_set = new HashMap<>();
    private HashMap<Integer, ArrayList<MCO>> micro_clusters = new HashMap<>();
    private ArrayList<MCO> PD = new ArrayList<>();
    // store list ob in increasing time arrival order
    private ArrayList<MCO> dataList = new ArrayList<>();
    private MTreeClass mtree = new MTreeClass();
    private HashSet<MCO> outlierList = new HashSet<>();
    private PriorityQueue<MCO> eventQueue = new PriorityQueue<>(new MCComparator());

    public MicroCluster_New(double maxDistance, int minNeighborCount, int windowSize, int slide) {
        this.maxDistance = maxDistance;
        this.minNeighborCount = minNeighborCount;
        this.windowSize = windowSize;
        this.slide = slide;
    }

    public ArrayList<Data> detectOutlier(ArrayList<Data> data, int currentTime) {
        if (slide != windowSize) {
            //purge expire object
            for (int i = dataList.size() - 1; i >= 0; i--) {
                MCO d = dataList.get(i);
                if (d.arrivalTime() <= currentTime - windowSize) {
                    //remove d from data List 
                    dataList.remove(i);

                    //if d is in cluster 
                    if (d.isInCluster) {
                        removeFromCluster(d);
                    }
                    //if d is PD 

                    removeFromPD(d);
                    //process event queue
                    process_event_queue(currentTime);

                }
            }
        } else {
            micro_clusters.clear();
            dataList.clear();
            dataList_set.clear();
            eventQueue.clear();
            mtree = null;
            mtree = new MTreeClass();
            PD.clear();
            outlierList.clear();
        }
        //process new data
        data.forEach(this::processNewData);

        //add result
        printStatistic();
        return new ArrayList<>(outlierList);
    }

    private void printStatistic() {
        int numPoints = computeNumberOfPointsInCluster();
        System.out.println("#points in clusters = " + numPoints);

        System.out.println("#points in event queue = " + eventQueue.size());

        System.out.println("avg neighborList length = " + computeAvgNeighborList());
    }

    private double computeAvgNeighborList() {
        double result = 0;
        for (MCO point : PD) {
            result += point.exps.size();
        }
        return result / PD.size();
    }

    private int computeNumberOfPointsInCluster() {
        int count = 0;
        for (ArrayList<MCO> points : micro_clusters.values()) {
            count += points.size();
        }
        return count;
    }

    //    public void addToHashMap(Integer o1, Integer o2) {
//        ArrayList<Integer> values = checkedPoints.get(o1);
//        if (values != null) {
//            values.add(o2);
//            checkedPoints.put(o1, values);
//        } else {
//            values = new ArrayList<>();
//            values.add(o2);
//            checkedPoints.put(o1, values);
//        }
//    }
//    public boolean checkInHashMap(Integer key, Integer v) {
//        ArrayList<Integer> values = checkedPoints.get(key);
//        return values != null && values.contains(v);
//    }
    private void removeFromCluster(MCO d) {
        //get the cluster
        ArrayList<MCO> cluster = micro_clusters.get(d.center);
        if (cluster != null) {
            cluster.remove(d);
            micro_clusters.put(d.center, cluster);

            //cluster is shrinked 
            if (cluster.size() < minNeighborCount + 1) {
                //remove this cluster from micro cluster list
                micro_clusters.remove(d.center);
                dataList_set.remove(d.center);
                cluster.sort(new MCComparatorArrivalTime());
                //process the objects in clusters 
                for (int i = 0; i < cluster.size(); i++) {
                    MCO o = cluster.get(i);
                    //reset all objects 
                    resetObject(o);
                    //put into PD 

                    o.numberOfSucceeding = o.numberOfSucceeding + cluster.size() - 1 - i;
                    addToPD(o, true);
                }

            }
        }
    }

    private void removeFromPD(MCO d) {
        //remove from pd
        PD.remove(d);
//        mtree.remove(d);

        //if d is in outlier list 
        if (d.numberOfSucceeding + d.exps.size() < minNeighborCount) {
            outlierList.remove(d);
        }

        outlierList.forEach((data) -> {
            while (data.exps.size() > 0 && data.exps.get(0) <= d.arrivalTime() + windowSize) {
                data.exps.remove(0);
                if (data.exps.isEmpty()) {
                    data.ev = 0;
                } else {
                    data.ev = data.exps.get(0);
                }
            }
        });
    }

    private void resetObject(MCO o) {
        o.exps.clear();
        o.Rmc.clear();
        o.isCenter = false;
        o.isInCluster = false;
        o.ev = 0;
        o.center = -1;
        o.numberOfSucceeding = 0;

    }

    private void addToPD(MCO o, boolean fromCluster) {
        PD.forEach((inPD) -> {
            //compute distance
            double distance = mtree.getDistanceFunction().calculate(o, inPD);
            if (distance <= maxDistance) {
                //check inPD is succeeding or preceding neighbor
                if (isSameSlide(inPD, o) == -1) {
                    //is preceeding neighbor
                    o.exps.add(inPD.arrivalTime() + windowSize);
                    if (!fromCluster) {
                        inPD.numberOfSucceeding++;
                    }
                } else if (isSameSlide(inPD, o) == 0) {
                    o.numberOfSucceeding++;
                    if (!fromCluster) {
                        inPD.numberOfSucceeding++;
                    }
                } else {
                    o.numberOfSucceeding++;
                    if (!fromCluster) {
                        inPD.exps.add(o.arrivalTime() + windowSize);
                    }

                }
                //just keep k-numberofSucceedingNeighbor
                if (!fromCluster) {
                    checkInlier(inPD);
                }

            }
        });

        //find neighbors in clusters (3R/2)
        ArrayList<Integer> clusters = findClusterIn3_2Range(o);
        clusters.stream().map((center_id) -> micro_clusters.get(center_id)).forEach((points) -> {
            points.stream().filter((p) -> (isNeighbor(p, o))).forEach((p) -> {
                if (isSameSlide(o, p) <= 0) {
                    //o is preceeding neighbor
                    o.numberOfSucceeding++;
                } else {
                    //p is preceeding neighbor
                    o.exps.add(p.arrivalTime() + windowSize);
                }
            });
        });

        //keep minNeighborCount-numberofSucceedingNeighbor of o
        checkInlier(o);

        PD.add(o);
//        mtree.add(o);
    }

    private int isSameSlide(MCO o1, MCO o2) {
        return Integer.compare((o1.arrivalTime() - 1) / slide, (o2.arrivalTime() - 1) / slide);
    }

    private int findNearestCenter(MCO d) {

        double min_distance = Double.MAX_VALUE;
        int min_center_id = -1;
        for (Integer center_id : micro_clusters.keySet()) {
            //get the center object
            MCO center = dataList_set.get(center_id);
            //compute the distance
            double distance = mtree.getDistanceFunction().calculate(center, d);

            if (distance < min_distance) {
                min_distance = distance;
                min_center_id = center_id;
            }
        }
        return min_center_id;
    }

    private ArrayList<Integer> findClusterIn3_2Range(MCO d) {
        ArrayList<Integer> result = new ArrayList<>();
        micro_clusters.keySet().forEach((center_id) -> {
            //get the center object
            MCO center = dataList_set.get(center_id);
            //compute the distance
            double distance = mtree.getDistanceFunction().calculate(center, d);
            if (distance <= maxDistance * 3.0 / 2) {
                result.add(center_id);
            }
        });
        return result;
    }

    private void processNewData(Data data) {
        MCO d = new MCO(data);

        //add to datalist
        dataList.add(d);

        int nearest_center_id = findNearestCenter(d);
        double min_distance = Double.MAX_VALUE;
        if (nearest_center_id > -1) { //found neareast cluster
            min_distance = mtree.getDistanceFunction().
                    calculate(dataList_set.get(nearest_center_id), d);
        }
        //assign to cluster if min distance <= R/2
        if (min_distance <= maxDistance / 2) {
            addToCluster(nearest_center_id, d);
        } else {
            //find all neighbors for d in PD that can  form a cluster
            ArrayList<MCO> neighborsInR2Distance = findNeighborR2InPD(d);
            if (neighborsInR2Distance.size() >= minNeighborCount * 1.1) {
                //form new cluster
                formNewCluster(d, neighborsInR2Distance);

            } else {
                //cannot form a new cluster
                addToPD(d, false);
            }
        }
        if (d.isCenter) {
            dataList_set.put(d.arrivalTime(), d);
        }
    }

    private void addToCluster(int nearest_center_id, MCO d) {
        //update for points in cluster
        d.isCenter = false;
        d.isInCluster = true;
        d.center = nearest_center_id;
        ArrayList<MCO> cluster = micro_clusters.get(nearest_center_id);
        cluster.add(d);
        micro_clusters.put(nearest_center_id, cluster);

        //update for points in PD that has Rmc list contains center
        PD.stream().filter((inPD) -> (inPD.Rmc.contains(nearest_center_id))).forEach((inPD) -> {
            //check if inPD is neighbor of d
            double distance = mtree.getDistanceFunction().
                    calculate(d, inPD);
            if (distance <= maxDistance) {
                if (isSameSlide(d, inPD) == -1) {
                    inPD.exps.add(d.arrivalTime() + windowSize);

                } else if (isSameSlide(d, inPD) >= 0) {
                    inPD.numberOfSucceeding++;
                }
                //mark inPD has checked with d
//                    addToHashMap(inPD.arrivalTime(),d.arrivalTime());
                //check if inPD become inlier
                checkInlier(inPD);
            }
        });

    }

    private ArrayList<MCO> findNeighborR2InPD(MCO d) {
        ArrayList<MCO> results = new ArrayList<>();
        PD.stream().filter((o) -> (mtree.getDistanceFunction().calculate(o, d) <= maxDistance * 1.0 / 2))
                .forEach(results::add);
        return results;
    }

    private boolean isOutlier(MCO d) {
        return d.numberOfSucceeding + d.exps.size() < minNeighborCount;
    }

    private void formNewCluster(MCO d, ArrayList<MCO> neighborsInR2Distance) {
        d.isCenter = true;
        d.isInCluster = true;
        d.center = d.arrivalTime();
        for (MCO data : neighborsInR2Distance) {
            PD.remove(data);
            if (isOutlier(data)) {
                outlierList.remove(data);
            } else {
                eventQueue.remove(data);
            }

            resetObject(data);
            data.isInCluster = true;
            data.center = d.arrivalTime();
            data.isCenter = false;
        }

        //add center to neighbor list
        neighborsInR2Distance.sort(new MCComparatorArrivalTime());
        neighborsInR2Distance.add(d);
        micro_clusters.put(d.arrivalTime(), neighborsInR2Distance);

        //update Rmc list
        ArrayList<MCO> list_rmc = findNeighborInR3_2InPD(d);
        for (MCO o : list_rmc) {
            if (isNeighbor(o, d)) {
                if (isSameSlide(o, d) <= 0) {
                    o.numberOfSucceeding++;
                } else {
                    o.exps.add(d.arrivalTime() + windowSize);
                }
                checkInlier(o);
            }
            o.Rmc.add(d.arrivalTime());
        }
    }

    private ArrayList<MCO> findNeighborInRInPD(MCO d) {
        ArrayList<MCO> result = new ArrayList<>();

        PD.stream().filter((o) -> (mtree.getDistanceFunction().calculate(o, d) <= maxDistance))
                .forEach(result::add);
        return result;
    }

    private ArrayList<MCO> findNeighborInR3_2InPD(MCO d) {
        ArrayList<MCO> result = new ArrayList<>();

        PD.forEach((p) -> {
            double distance = mtree.getDistanceFunction().calculate(p, d);
            if (distance <= maxDistance * 3.0 / 2) {
                result.add(p);
            }
        });
        return result;
    }

    private void checkInlier(MCO inPD) {
        Collections.sort(inPD.exps);

        while (inPD.exps.size() > minNeighborCount - inPD.numberOfSucceeding && inPD.exps.size() > 0) {
            inPD.exps.remove(0);
        }
        if (inPD.exps.size() > 0) {
            inPD.ev = inPD.exps.get(0);
        } else {
            inPD.ev = 0;
        }

        if (inPD.exps.size() + inPD.numberOfSucceeding >= minNeighborCount) {
            if (inPD.numberOfSucceeding >= minNeighborCount) {

                eventQueue.remove(inPD);

                outlierList.remove(inPD);
            } else {

                outlierList.remove(inPD);
                if (!eventQueue.contains(inPD)) {
                    eventQueue.add(inPD);
                }
            }
        } else {
            eventQueue.remove(inPD);
            outlierList.add(inPD);
        }
    }

    private boolean isNeighbor(MCO p, MCO o) {
        double d = mtree.getDistanceFunction().calculate(p, o);
        return d <= maxDistance;
    }

    private void process_event_queue(int currentTime) {
        MCO x = eventQueue.peek();

        while (x != null && x.ev <= currentTime) {

            x = eventQueue.poll();
            while (x.exps.get(0) <= currentTime) {
                x.exps.remove(0);
                if (x.exps.isEmpty()) {
                    x.ev = 0;
                    break;
                } else {
                    x.ev = x.exps.get(0);
                }
            }
            if (x.exps.size() + x.numberOfSucceeding < minNeighborCount) {

                outlierList.add(x);

            } else if (x.numberOfSucceeding < minNeighborCount
                    && x.exps.size() + x.numberOfSucceeding >= minNeighborCount) {
                eventQueue.add(x);
            }

            x = eventQueue.peek();

        }
    }

    static class MCComparator implements Comparator<MCO> {

        @Override
        public int compare(MCO o1, MCO o2) {
            return Integer.compare(o1.ev, o2.ev);
        }

    }

    static class MCComparatorArrivalTime implements Comparator<MCO> {

        @Override
        public int compare(MCO o1, MCO o2) {
            return Integer.compare(o1.arrivalTime(), o2.arrivalTime());
        }

    }

    static class MCO extends Data {

        int center;
        ArrayList<Integer> exps;
        ArrayList<Integer> Rmc;

        int ev;
        boolean isInCluster;
        boolean isCenter;

        int numberOfSucceeding;

        MCO(Data d) {
            super(d.arrivalTime(), d.values);

            exps = new ArrayList<>();
            Rmc = new ArrayList<>();
            isCenter = false;
            isInCluster = false;
        }

    }
}
