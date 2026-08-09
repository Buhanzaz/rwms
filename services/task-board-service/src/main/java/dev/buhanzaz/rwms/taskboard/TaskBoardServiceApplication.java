package dev.buhanzaz.rwms.taskboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Boots the task-board domain owner, its API, persistence, scheduling, and event runtime. */
@SpringBootApplication
public class TaskBoardServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(TaskBoardServiceApplication.class, args);
  }
}
