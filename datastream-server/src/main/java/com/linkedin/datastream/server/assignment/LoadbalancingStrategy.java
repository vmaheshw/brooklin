package com.linkedin.datastream.server.assignment;

import com.linkedin.datastream.common.Datastream;
import com.linkedin.datastream.server.AssignmentStrategy;
import com.linkedin.datastream.server.DatastreamTask;
import com.linkedin.datastream.server.DatastreamTaskImpl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Creates the datastreamTasks only for the new datastreams. The number of tasks created for datastream is
 * min(NumberOfPartitionsInDatastream, numberOfInstances * PARTITIONING_FACTOR).
 * These datastreamTasks are sorted by the datastreamName.
 * These tasks are then redistributed across all the instances equally.
 */
public class LoadbalancingStrategy implements AssignmentStrategy {

  private static final Logger LOG = LoggerFactory.getLogger(LoadbalancingStrategy.class.getName());

  private static final int PARTITIONING_FACTOR = 2;

  @Override
  public Map<String, Set<DatastreamTask>> assign(List<Datastream> datastreams, List<String> instances,
      Map<String, Set<DatastreamTask>> currentAssignment) {

    // if there are no live instances, return empty assignment
    if (instances.size() == 0) {
      return new HashMap<>();
    }

    int maxTasksPerDatastream = instances.size() * PARTITIONING_FACTOR;
    datastreams = sortDatastreams(datastreams);
    List<DatastreamTask> datastreamTasks = getDatastreamTasks(datastreams, currentAssignment, maxTasksPerDatastream);
    Collections.sort(instances);

    Map<String, Set<DatastreamTask>> assignment = new HashMap<>();
    instances.forEach(i -> assignment.put(i, new HashSet<>()));

    // Distribute the datastream tasks across all the instances.
    int datastreamTaskLength = datastreamTasks.size();
    for (int i = 0; i < datastreamTaskLength; i++) {
      String instanceName = instances.get(i % instances.size());
      assignment.get(instanceName).add(datastreamTasks.get(i));
    }

    LOG.info(String.format("Datastreams: %s, instances: %s, currentAssignment: %s, NewAssignment: %s",
        datastreams.stream().map(x -> x.getName()).collect(Collectors.toList()),
        instances, currentAssignment, assignment));

    return assignment;
  }

  private List<Datastream> sortDatastreams(List<Datastream> datastreams) {
    Map<String, Datastream> m = new HashMap<>();
    List<String> keys = new ArrayList<>();
    datastreams.forEach(ds -> {
      m.put(ds.getName(), ds);
      keys.add(ds.getName());
    });

    Collections.sort(keys);

    List<Datastream> result = new ArrayList<>();
    keys.forEach(k -> result.add(m.get(k)));

    return result;
  }

  private List<DatastreamTask> getDatastreamTasks(List<Datastream> datastreams,
      Map<String, Set<DatastreamTask>> currentAssignment, int maxTasksPerDatastream) {
    Set<DatastreamTask> allTasks = new HashSet<>();

    List<DatastreamTask> currentlyAssignedDatastreamTasks = new ArrayList<>();
    currentAssignment.values().forEach(currentlyAssignedDatastreamTasks::addAll);

    for(Datastream datastream : datastreams) {
      Set<DatastreamTask> tasksForDatastream = new HashSet<>();
      currentlyAssignedDatastreamTasks.stream().filter(x -> x.getDatastreams().contains(datastream.getName())).forEach(tasksForDatastream::add);
      // If there are no datastream tasks that are currently assigned for this datastream.
      if(tasksForDatastream.isEmpty()) {
        tasksForDatastream = createTasksForDatastream(datastream, maxTasksPerDatastream);
      }

      allTasks.addAll(tasksForDatastream);
    }

    return new ArrayList<>(allTasks);
  }

  private Set<DatastreamTask> createTasksForDatastream(Datastream datastream, int maxTasksPerDatastream) {
    int numberOfDatastreamPartitions = 1;
    if(datastream.hasSource() && datastream.getSource().hasPartitions()) {
      numberOfDatastreamPartitions = datastream.getSource().getPartitions();
    }
    int tasksPerDatastream = maxTasksPerDatastream < numberOfDatastreamPartitions ? maxTasksPerDatastream : numberOfDatastreamPartitions;
    Set<DatastreamTask> tasks = new HashSet<>();
    for(int index = 0; index < tasksPerDatastream; index++) {
      tasks.add(new DatastreamTaskImpl(datastream, Integer.toString(index),
          assignPartitionsToTask(datastream, index, tasksPerDatastream)));
    }

    return tasks;
  }

  // Finds the list of partitions that the current datastream task should own.
  // Based on the task index, total number of tasks in the datastream and the total partitions in the datastream source
  private List<Integer> assignPartitionsToTask(Datastream datastream, int taskIndex, int totalTasks) {
    List<Integer> partitions = new ArrayList<>();
    if(datastream.hasSource() && datastream.getSource().hasPartitions()) {
      int numPartitions = datastream.getSource().getPartitions();
      for(int index = 0; index + taskIndex < numPartitions; index += totalTasks) {
        partitions.add(index + taskIndex);
      }
    }

    return partitions;
  }
}
