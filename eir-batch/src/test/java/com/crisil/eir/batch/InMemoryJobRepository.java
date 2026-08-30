package com.crisil.eir.batch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.dao.ExecutionContextDao;
import org.springframework.batch.core.repository.dao.JobExecutionDao;
import org.springframework.batch.core.repository.dao.JobInstanceDao;
import org.springframework.batch.core.repository.dao.StepExecutionDao;
import org.springframework.batch.core.repository.support.SimpleJobRepository;
import org.springframework.batch.item.ExecutionContext;

/**
 * An in-memory Spring Batch job repository, for the restart tests.
 *
 * <h2>Why four DAOs and not one {@code JobRepository}</h2>
 *
 * <p>Because the thing under test is restart semantics, and those live in Spring Batch's own code:
 * {@code SimpleJobRepository.createJobExecution} decides whether a second launch is a restart, a
 * refusal or a new instance; {@code SimpleStepHandler} decides whether a step whose last execution
 * completed runs again; {@code SimpleStepExecutionSplitter} decides which <em>partitions</em> a
 * restart re-runs. A hand-written {@code JobRepository} would put my own reading of those rules
 * underneath a test whose whole purpose is to check that this module composes with them correctly —
 * the test would then pass against my reading and say nothing about Spring Batch's.
 *
 * <p>So the four persistence interfaces are implemented instead and the real
 * {@link SimpleJobRepository} sits on top. Everything decision-shaped comes from the framework.
 *
 * <h2>Why an in-memory one at all</h2>
 *
 * <p>Spring Batch 5 removed the map-backed job repository, and the JDBC one needs a database.
 * Neither an embedded database nor {@code spring-batch-test} is in this repository's local Maven
 * cache, and the module's constraint is that it resolves fully offline — an air-gapped bank CI has no
 * Central. The restart property being asserted does not depend on where the metadata lives: what
 * matters is that a failed execution's step and partition records survive to be read by the next
 * launch, which they do here.
 *
 * <h2>Fidelity, and the two places it is deliberately imperfect</h2>
 *
 * <ul>
 *   <li><b>Execution contexts are copied on save and on read</b> rather than shared by reference, so
 *       a test cannot accidentally pass because a tasklet mutated the object the "previous
 *       execution" holds. That is the JDBC behaviour and it is the behaviour the plan-fingerprint
 *       check in {@code AmortisationBatchJob} has to survive.</li>
 *   <li><b>{@code synchronizeStatus} is a no-op.</b> It exists to refresh an in-memory
 *       {@code JobExecution} from a row another process updated, and there is no other process.</li>
 * </ul>
 */
final class InMemoryJobRepository {

    private InMemoryJobRepository() {
    }

    /** A fresh, empty repository. */
    static JobRepository create() {
        Instances instances = new Instances();
        Executions executions = new Executions();
        Steps steps = new Steps();
        Contexts contexts = new Contexts();
        return new SimpleJobRepository(instances, executions, steps, contexts);
    }

    /**
     * The identity of a job instance: job name plus the <em>identifying</em> parameters.
     *
     * <p>Identifying only, which is what makes {@code AmortisationBatchJob.parametersFor} work as
     * documented: the run id identifies the run, and the boundary instant rides along on the record
     * without making a re-run at a different boundary look like a different run.
     */
    private static String key(String jobName, JobParameters parameters) {
        Map<String, Object> identifying = new LinkedHashMap<>();
        parameters.getIdentifyingParameters().forEach(
            (name, parameter) -> identifying.put(name, parameter.getValue()));
        return jobName + identifying;
    }

    private static final class Instances implements JobInstanceDao {

        private final AtomicLong ids = new AtomicLong();
        private final Map<String, JobInstance> byKey = new LinkedHashMap<>();
        private final Map<Long, JobInstance> byId = new LinkedHashMap<>();

        @Override
        public synchronized JobInstance createJobInstance(
            String jobName, JobParameters jobParameters) {
            JobInstance instance = new JobInstance(ids.incrementAndGet(), jobName);
            byKey.put(key(jobName, jobParameters), instance);
            byId.put(instance.getId(), instance);
            return instance;
        }

        @Override
        public synchronized JobInstance getJobInstance(
            String jobName, JobParameters jobParameters) {
            return byKey.get(key(jobName, jobParameters));
        }

        @Override
        public synchronized JobInstance getJobInstance(Long instanceId) {
            return byId.get(instanceId);
        }

        @Override
        public synchronized JobInstance getJobInstance(JobExecution jobExecution) {
            return jobExecution.getJobInstance();
        }

        @Override
        public synchronized List<JobInstance> getJobInstances(String jobName, int start, int count) {
            return findJobInstancesByName(jobName, start, count);
        }

        @Override
        public synchronized List<String> getJobNames() {
            Set<String> names = new LinkedHashSet<>();
            for (JobInstance instance : byId.values()) {
                names.add(instance.getJobName());
            }
            return new ArrayList<>(names);
        }

        @Override
        public synchronized List<JobInstance> findJobInstancesByName(
            String jobName, int start, int count) {
            List<JobInstance> matching = new ArrayList<>();
            for (JobInstance instance : byId.values()) {
                if (instance.getJobName().equals(jobName)) {
                    matching.add(instance);
                }
            }
            // Newest first, which is the JDBC dao's contract.
            matching.sort(Comparator.comparing(JobInstance::getId).reversed());
            int from = Math.min(start, matching.size());
            int to = Math.min(from + count, matching.size());
            return new ArrayList<>(matching.subList(from, to));
        }

        @Override
        public synchronized long getJobInstanceCount(String jobName) throws NoSuchJobException {
            long count = 0;
            for (JobInstance instance : byId.values()) {
                if (instance.getJobName().equals(jobName)) {
                    count++;
                }
            }
            if (count == 0) {
                throw new NoSuchJobException("no job instances for " + jobName);
            }
            return count;
        }
    }

    private static final class Executions implements JobExecutionDao {

        private final AtomicLong ids = new AtomicLong();
        private final Map<Long, JobExecution> byId = new LinkedHashMap<>();

        @Override
        public synchronized void saveJobExecution(JobExecution jobExecution) {
            jobExecution.setId(ids.incrementAndGet());
            jobExecution.incrementVersion();
            byId.put(jobExecution.getId(), jobExecution);
        }

        @Override
        public synchronized void updateJobExecution(JobExecution jobExecution) {
            jobExecution.incrementVersion();
            byId.put(jobExecution.getId(), jobExecution);
        }

        @Override
        public synchronized List<JobExecution> findJobExecutions(JobInstance jobInstance) {
            List<JobExecution> matching = new ArrayList<>();
            for (JobExecution execution : byId.values()) {
                if (execution.getJobInstance().getId().equals(jobInstance.getId())) {
                    matching.add(execution);
                }
            }
            // Descending by id — SimpleJobRepository.createJobExecution walks this list looking for
            // a running or completed execution, and getLastJobExecution takes the head.
            matching.sort(Comparator.comparing(JobExecution::getId).reversed());
            return matching;
        }

        @Override
        public synchronized JobExecution getLastJobExecution(JobInstance jobInstance) {
            List<JobExecution> executions = findJobExecutions(jobInstance);
            return executions.isEmpty() ? null : executions.get(0);
        }

        @Override
        public synchronized Set<JobExecution> findRunningJobExecutions(String jobName) {
            Set<JobExecution> running = new LinkedHashSet<>();
            for (JobExecution execution : byId.values()) {
                if (execution.getJobInstance().getJobName().equals(jobName)
                    && execution.isRunning()) {
                    running.add(execution);
                }
            }
            return running;
        }

        @Override
        public synchronized JobExecution getJobExecution(Long executionId) {
            return byId.get(executionId);
        }

        @Override
        public synchronized void synchronizeStatus(JobExecution jobExecution) {
            // Nothing to synchronize: there is one process and the stored object is the live one.
            // See the class javadoc.
        }
    }

    private static final class Steps implements StepExecutionDao {

        private final AtomicLong ids = new AtomicLong();
        private final List<StepExecution> all = new ArrayList<>();

        @Override
        public synchronized void saveStepExecution(StepExecution stepExecution) {
            stepExecution.setId(ids.incrementAndGet());
            stepExecution.incrementVersion();
            all.add(stepExecution);
        }

        @Override
        public synchronized void saveStepExecutions(
            java.util.Collection<StepExecution> stepExecutions) {
            for (StepExecution stepExecution : stepExecutions) {
                saveStepExecution(stepExecution);
            }
        }

        @Override
        public synchronized void updateStepExecution(StepExecution stepExecution) {
            stepExecution.incrementVersion();
            if (!all.contains(stepExecution)) {
                all.add(stepExecution);
            }
        }

        @Override
        public synchronized StepExecution getStepExecution(
            JobExecution jobExecution, Long stepExecutionId) {
            for (StepExecution stepExecution : all) {
                if (stepExecutionId.equals(stepExecution.getId())) {
                    return stepExecution;
                }
            }
            return null;
        }

        /**
         * The most recent execution of {@code stepName} within {@code jobInstance}.
         *
         * <p><b>The method the restart turns on.</b> {@code SimpleStepHandler} asks it to decide
         * whether a completed step runs again, and {@code SimpleStepExecutionSplitter} asks it once
         * per partition to decide which partitions a restart re-runs. "Most recent" is by step
         * execution id, which is monotonic here by construction, because the ordering must be by
         * when the execution was created and not by when it was last updated: a partition that
         * failed early in execution 1 and completed in execution 2 has an older last-update than one
         * that ran late in execution 1, and ordering by update time would answer with the wrong
         * attempt.
         */
        @Override
        public synchronized StepExecution getLastStepExecution(
            JobInstance jobInstance, String stepName) {
            StepExecution latest = null;
            for (StepExecution stepExecution : all) {
                if (!stepExecution.getStepName().equals(stepName)) {
                    continue;
                }
                if (!stepExecution.getJobExecution().getJobInstance().getId()
                    .equals(jobInstance.getId())) {
                    continue;
                }
                if (latest == null || stepExecution.getId() > latest.getId()) {
                    latest = stepExecution;
                }
            }
            return latest;
        }

        @Override
        public synchronized void addStepExecutions(JobExecution jobExecution) {
            for (StepExecution stepExecution : all) {
                if (stepExecution.getJobExecutionId().equals(jobExecution.getId())
                    && !jobExecution.getStepExecutions().contains(stepExecution)) {
                    jobExecution.getStepExecutions().add(stepExecution);
                }
            }
        }

        @Override
        public synchronized long countStepExecutions(JobInstance jobInstance, String stepName) {
            long count = 0;
            for (StepExecution stepExecution : all) {
                if (stepExecution.getStepName().equals(stepName)
                    && stepExecution.getJobExecution().getJobInstance().getId()
                        .equals(jobInstance.getId())) {
                    count++;
                }
            }
            return count;
        }
    }

    private static final class Contexts implements ExecutionContextDao {

        private final Map<Long, ExecutionContext> jobContexts = new LinkedHashMap<>();
        private final Map<Long, ExecutionContext> stepContexts = new LinkedHashMap<>();

        @Override
        public synchronized ExecutionContext getExecutionContext(JobExecution jobExecution) {
            return copy(jobContexts.get(jobExecution.getId()));
        }

        @Override
        public synchronized ExecutionContext getExecutionContext(StepExecution stepExecution) {
            return copy(stepContexts.get(stepExecution.getId()));
        }

        @Override
        public synchronized void saveExecutionContext(JobExecution jobExecution) {
            jobContexts.put(jobExecution.getId(), copy(jobExecution.getExecutionContext()));
        }

        @Override
        public synchronized void saveExecutionContext(StepExecution stepExecution) {
            stepContexts.put(stepExecution.getId(), copy(stepExecution.getExecutionContext()));
        }

        @Override
        public synchronized void saveExecutionContexts(
            java.util.Collection<StepExecution> stepExecutions) {
            for (StepExecution stepExecution : stepExecutions) {
                saveExecutionContext(stepExecution);
            }
        }

        @Override
        public synchronized void updateExecutionContext(JobExecution jobExecution) {
            saveExecutionContext(jobExecution);
        }

        @Override
        public synchronized void updateExecutionContext(StepExecution stepExecution) {
            saveExecutionContext(stepExecution);
        }

        /** Copied, never shared — see the class javadoc. */
        private static ExecutionContext copy(ExecutionContext context) {
            return context == null ? new ExecutionContext() : new ExecutionContext(context);
        }
    }
}
