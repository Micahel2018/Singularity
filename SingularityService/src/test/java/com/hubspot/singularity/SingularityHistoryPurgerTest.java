package com.hubspot.singularity;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.mesos.Protos.TaskState;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.Handle;

import com.google.common.base.Optional;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.hubspot.singularity.config.HistoryPurgingConfiguration;
import com.hubspot.singularity.data.history.HistoryManager;
import com.hubspot.singularity.data.history.SingularityHistoryPurger;
import com.hubspot.singularity.data.history.SingularityTaskHistoryPersister;

import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.FileSystemResourceAccessor;

public class SingularityHistoryPurgerTest extends SingularitySchedulerTestBase {

  @Inject
  protected Provider<DBI> dbiProvider;

  @Inject
  protected HistoryManager historyManager;

  @Inject
  protected SingularityTaskHistoryPersister taskHistoryPersister;

  public SingularityHistoryPurgerTest() {
    super(true);
  }

  @Before
  public void createTestData() throws Exception {
    Handle handle = dbiProvider.get().open();

    Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(handle.getConnection()));

    Liquibase liquibase = new Liquibase("singularity_test.sql", new FileSystemResourceAccessor(), database);
    liquibase.update((String) null);

    try {
      database.close();
    } catch (Throwable t) {
    }

    handle.close();
  }

  @After
  public void blowDBAway() {
    Handle handle = dbiProvider.get().open();

    handle.execute("DELETE FROM taskHistory;DELETE FROM requestHistory;DELETE FROM deployHistory;");

    handle.close();
  }

  private SingularityTaskHistory buildTask(long launchTime) {
    Optional<String> directory = Optional.absent();
    List<SingularityTaskHealthcheckResult> hcs = Collections.emptyList();
    List<SingularityLoadBalancerUpdate> upds = Collections.emptyList();
    List<SingularityTaskHistoryUpdate> historyUpdates = Collections.emptyList();
    List<SingularityTaskShellCommandHistory> shellHistory = Collections.emptyList();

    SingularityTask task = prepTask(request, firstDeploy, launchTime, 1);

    SingularityTaskHistory taskHistory = new SingularityTaskHistory(historyUpdates, directory, hcs, task, upds, shellHistory);

    return taskHistory;
  }

  private void saveTasks(int num, long launchTime) {
    for (int i = 0; i < num; i++) {
      SingularityTaskHistory taskHistory = buildTask(launchTime + i);

      historyManager.saveTaskHistory(taskHistory);
    }
  }


  @Test
  public void historyUpdaterTest() {
    initRequest();
    initFirstDeploy();

    HistoryPurgingConfiguration historyPurgingConfiguration = new HistoryPurgingConfiguration();
    historyPurgingConfiguration.setEnabled(true);
    historyPurgingConfiguration.setDeleteTaskHistoryBytesInsteadOfEntireRow(false);
    historyPurgingConfiguration.setDeleteTaskHistoryAfterDays(1);

    SingularityTaskHistory taskHistory = buildTask(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(3));

    historyManager.saveTaskHistory(taskHistory);

    Assert.assertTrue(historyManager.getTaskHistory(taskHistory.getTask().getTaskId().getId()).get().getTask() != null);

    Assert.assertEquals(1, historyManager.getTaskHistoryForRequest(requestId, 0, 100).size());

    SingularityHistoryPurger purger = new SingularityHistoryPurger(historyPurgingConfiguration, historyManager);

    purger.runActionOnPoll();

    Assert.assertEquals(1, historyManager.getTaskHistoryForRequest(requestId, 0, 100).size());

    Assert.assertTrue(!historyManager.getTaskHistory(taskHistory.getTask().getTaskId().getId()).isPresent());
  }

  @Test
  public void historyPurgerTest() {
    initRequest();
    initFirstDeploy();

    saveTasks(3, System.currentTimeMillis());

    Assert.assertEquals(3, historyManager.getTaskHistoryForRequest(requestId, 0, 10).size());

    HistoryPurgingConfiguration historyPurgingConfiguration = new HistoryPurgingConfiguration();
    historyPurgingConfiguration.setEnabled(true);
    historyPurgingConfiguration.setDeleteTaskHistoryBytesInsteadOfEntireRow(true);
    historyPurgingConfiguration.setDeleteTaskHistoryAfterDays(10);

    SingularityHistoryPurger purger = new SingularityHistoryPurger(historyPurgingConfiguration, historyManager);

    purger.runActionOnPoll();

    Assert.assertEquals(3, historyManager.getTaskHistoryForRequest(requestId, 0, 10).size());

    historyPurgingConfiguration.setDeleteTaskHistoryAfterTasksPerRequest(1);

    purger.runActionOnPoll();

    Assert.assertEquals(1, historyManager.getTaskHistoryForRequest(requestId, 0, 10).size());

    historyPurgingConfiguration.setDeleteTaskHistoryAfterTasksPerRequest(25);
    historyPurgingConfiguration.setDeleteTaskHistoryAfterDays(100);

    purger.runActionOnPoll();

    Assert.assertEquals(1, historyManager.getTaskHistoryForRequest(requestId, 0, 10).size());

    saveTasks(100, System.currentTimeMillis() - TimeUnit.DAYS.toMillis(200));

    purger.runActionOnPoll();

    Assert.assertEquals(1, historyManager.getTaskHistoryForRequest(requestId, 0, 10).size());
  }

  @Test
  public void testRunId() {
    initScheduledRequest();
    initFirstDeploy();

    String runId = "my-run-id";
    String user = "my-user";

    SingularityPendingRequestParent parent = requestResource.scheduleImmediately(requestId, Optional.of(user), Optional.of(runId), Collections.<String> emptyList());

    Assert.assertEquals(runId, parent.getPendingRequest().getRunId().get());
    Assert.assertEquals(user, parent.getPendingRequest().getUser().get());

    resourceOffers();

    Assert.assertEquals(runId, taskManager.getActiveTasks().get(0).getTaskRequest().getPendingTask().getRunId().get());
    Assert.assertEquals(user, taskManager.getActiveTasks().get(0).getTaskRequest().getPendingTask().getUser().get());

    SingularityTaskId taskId = taskManager.getActiveTaskIds().get(0);

    statusUpdate(taskManager.getTask(taskId).get(), TaskState.TASK_FINISHED);

    taskHistoryPersister.runActionOnPoll();

    Assert.assertEquals(runId, historyManager.getTaskHistory(taskId.getId()).get().getTask().getTaskRequest().getPendingTask().getRunId().get());
    Assert.assertEquals(runId, historyManager.getTaskHistoryForRequest(requestId, 0, 10).get(0).getRunId().get());

    parent = requestResource.scheduleImmediately(requestId, Optional.<String> absent(), Optional.<String> absent(), Collections.<String> emptyList());

    Assert.assertTrue(parent.getPendingRequest().getRunId().isPresent());
  }

}
