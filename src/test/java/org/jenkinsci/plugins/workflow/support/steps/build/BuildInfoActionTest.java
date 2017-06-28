package org.jenkinsci.plugins.workflow.support.steps.build;

import hudson.model.Job;
import jenkins.model.Jenkins;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.same;
import static org.junit.Assert.assertEquals;
import static org.powermock.api.mockito.PowerMockito.when;

import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

@RunWith(PowerMockRunner.class)
@PrepareForTest(Jenkins.class)
public class BuildInfoActionTest {
    private Jenkins jenkins;

    @Before
    public void setUp() {
        PowerMockito.mockStatic(Jenkins.class);
        jenkins = PowerMockito.mock(Jenkins.class);
        PowerMockito.when(Jenkins.getInstance()).thenReturn(jenkins);
        PowerMockito.when(Jenkins.getActiveInstance()).thenReturn(jenkins);
    }

    @Test
    public void testConcurrentAccess() throws Exception {
        Job item = Mockito.mock(Job.class);
        when(jenkins.getItemByFullName(any(String.class), same(Job.class))).thenReturn(item);

        BuildInfoAction buildInfoAction = new BuildInfoAction("firstOne", 1);

        ExecutorService executors = Executors.newFixedThreadPool(5);
        Runnable task1 = createRunnable("A", buildInfoAction);
        Runnable task2 = createRunnable("B", buildInfoAction);
        Runnable task3 = createRunnable("C", buildInfoAction);
        Runnable task4 = createRunnable("D", buildInfoAction);
        Runnable task5 = createRunnable("E", buildInfoAction);

        Future future1 = executors.submit(task1);
        Future future2 = executors.submit(task2);
        Future future3 = executors.submit(task3);
        Future future4 = executors.submit(task4);
        Future future5 = executors.submit(task5);

        while (true) {
            if (future1.isDone() && future2.isDone() && future3.isDone() && future4.isDone() && future5.isDone() ) {
                break;
            }

            Thread.sleep(100);
        }

        assertEquals(501, buildInfoAction.getChildBuilds().size());
    }

    private Runnable createRunnable(final String name, final BuildInfoAction action) {
        return new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 100; i++) {
                    action.addBuildInfo(name, i + 1);
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        };
    }
}
