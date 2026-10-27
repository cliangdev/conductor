package com.conductor.scheduling;

/** A runnable that knows which registered job it ticks, so job timing logs can name it. */
public interface NamedJob extends Runnable {
    String jobName();
}
