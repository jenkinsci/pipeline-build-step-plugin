package org.jenkinsci.plugins.workflow.support.steps.build;

import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.console.AnnotatedLargeText;

class DownstreamBuildWriter implements Runnable {
    private Thread t;
    private String threadName;
    private Run downstreamRun;
    private TaskListener taskListener;

    DownstreamBuildWriter(String name, Run run, TaskListener listener) {
        threadName = name;
        downstreamRun = run;
        taskListener = listener;
    }
    
    private long writeLog(long pos, AnnotatedLargeText logText) {      
        try {
            logText = downstreamRun.getLogText();            
            pos = logText.writeRawLogTo(pos, taskListener.getLogger());
        } catch(java.io.IOException e) {}
        return pos;
    }

    public void run() {
        long pos = 0;
        AnnotatedLargeText logText;
        try {
            logText = downstreamRun.getLogText();
            pos = writeLog(pos, logText);
            while (!logText.isComplete()) {
                Thread.sleep(1000);
                logText = downstreamRun.getLogText();
                pos = writeLog(pos, logText);
            }
        } catch (InterruptedException e) {
            logText = downstreamRun.getLogText();
            writeLog(pos, logText);
        }
    }

    public void start () {
        if (t == null) {
           t = new Thread (this, threadName);
           t.start ();
        }
     }
}
