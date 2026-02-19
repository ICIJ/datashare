package org.icij.datashare.asynctasks.temporal;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import javax.tools.JavaFileObject;
import org.junit.Test;

public class TemporalWorkflowGeneratorTest {
    private static final JavaFileObject taskSources = JavaFileObjects.forSourceString(
        "org.icij.datashare.asynctasks.temporal.HelloWorldTask",
        """
            package org.icij.datashare.asynctasks.temporal;
            
            import java.util.concurrent.Callable;
            
            import org.icij.datashare.asynctasks.temporal.ActivityOpts;
            import org.icij.datashare.asynctasks.temporal.TemporalSingleActivityWorkflow;
            
            @TemporalSingleActivityWorkflow(name = "hello-world", activityOptions = @ActivityOpts(taskQueue = "hello-world-queue", timeout = "2d", retriables = {Exception.class}))
            public class HelloWorldTask implements Callable<String> {
                @Override
                public String call() {
                    return "hello world";
                }
            }
            """
    );

    @Test
    public void test_should_generate_workflow_interface() {
        Compilation compilation = javac().withProcessors(new TemporalWorkflowGenerator()).compile(taskSources);

        assertThat(compilation).succeeded();
        JavaFileObject expectedSource = JavaFileObjects.forSourceString(
            "org.icij.datashare.asynctask.temporal.HelloWorldWorkflow",
            """
                package org.icij.datashare.asynctasks.temporal;
                
                import io.temporal.workflow.WorkflowInterface;
                import io.temporal.workflow.WorkflowMethod;
                import java.lang.Exception;
                import java.lang.Object;
                import java.lang.String;
                import java.util.Map;
                
                @WorkflowInterface
                public interface HelloWorldWorkflow {
                    @WorkflowMethod(name = "org.icij.datashare.asynctasks.temporal.HelloWorldTask")
                    String run(final Map<String, Object> args) throws Exception;
                }
                """
        );
        assertThat(compilation)
            .generatedSourceFile("org.icij.datashare.asynctasks.temporal.HelloWorldWorkflow")
            .hasSourceEquivalentTo(expectedSource);
    }

    @Test
    public void test_should_generate_workflow_impl() {
        Compilation compilation = javac().withProcessors(new TemporalWorkflowGenerator()).compile(taskSources);

        assertThat(compilation).succeeded();
        JavaFileObject expectedSource = JavaFileObjects.forSourceString(
            "org.icij.datashare.asynctask.temporal.HelloWorldWorkflowImpl",
            """
                package org.icij.datashare.asynctasks.temporal;
                
                import io.temporal.activity.ActivityOptions;
                import io.temporal.workflow.Workflow;
                import java.lang.Exception;
                import java.lang.Object;
                import java.lang.Override;
                import java.lang.String;
                import java.util.Map;
           
                public class HelloWorldWorkflowImpl extends TemporalWorkflowImpl implements HelloWorldWorkflow {
                    private final HelloWorldActivity activity;
           
                    public HelloWorldWorkflowImpl() {
                        this.activity = Workflow.newActivityStub(
                            HelloWorldActivity.class,
                            ActivityOptions.newBuilder()
                                .setTaskQueue("hello-world-queue")
                                .setStartToCloseTimeout(java.time.Duration.parse("2d"))
                                .build()
                            );
                    }
            
                    @Override
                    public String run(final Map<String, Object> args) throws Exception {
                        return this.activity.run(args);
                    }
                }
                """
        );
        assertThat(compilation)
            .generatedSourceFile("org.icij.datashare.asynctasks.temporal.HelloWorldWorkflowImpl")
            .hasSourceEquivalentTo(expectedSource);
    }

    @Test
    public void test_should_generate_activity_interface() {
        Compilation compilation = javac().withProcessors(new TemporalWorkflowGenerator()).compile(taskSources);

        assertThat(compilation).succeeded();
        JavaFileObject expectedSource = JavaFileObjects.forSourceString(
            "org.icij.datashare.asynctask.temporal.HelloWorldActivity",
            """
                package org.icij.datashare.asynctasks.temporal;
                
                import io.temporal.activity.ActivityInterface;
                import io.temporal.activity.ActivityMethod;
                import java.lang.Exception;
                import java.lang.Object;
                import java.lang.String;
                import java.util.Map;
           
                @ActivityInterface
                public interface HelloWorldActivity {
                    @ActivityMethod(name = "org.icij.datashare.asynctasks.temporal.HelloWorldTask")
                    String run(final Map<String, Object> args) throws Exception;
                }
                """
        );
        assertThat(compilation)
            .generatedSourceFile("org.icij.datashare.asynctasks.temporal.HelloWorldActivity")
            .hasSourceEquivalentTo(expectedSource);
    }

    @Test
    public void test_should_generate_activity_impl() {
        Compilation compilation = javac().withProcessors(new TemporalWorkflowGenerator()).compile(taskSources);

        assertThat(compilation).succeeded();
        JavaFileObject expectedSource = JavaFileObjects.forSourceString(
            "org.icij.datashare.asynctasks.temporal.HelloWorldActivityImpl",
            """
                package org.icij.datashare.asynctasks.temporal;
                
                import io.temporal.client.WorkflowClient;
                import java.lang.Class;
                import java.lang.Double;
                import java.lang.Exception;
                import java.lang.Object;
                import java.lang.Override;
                import java.lang.String;
                import java.util.Map;
                import java.util.Set;
                import org.icij.datashare.asynctasks.TaskFactory;
           
                public class HelloWorldActivityImpl extends TemporalActivityImpl<String, HelloWorldTask> implements HelloWorldActivity {

                    public HelloWorldActivityImpl(TaskFactory factory, WorkflowClient client, Double progressWeight) {
                        super(factory, client, progressWeight);
                    }
                
                    @Override
                    protected Class<HelloWorldTask> getTaskClass() {
                        return HelloWorldTask.class;
                    }
               
                    @Override
                    protected Set<Class<? extends Exception>> getRetriables() {
                        return Set.of(Exception.class);
                    }

                    @Override
                    public String run(final Map<String, Object> args) throws Exception {
                        return super.run(args);
                    }
                }
                """
        );
        assertThat(compilation)
            .generatedSourceFile("org.icij.datashare.asynctasks.temporal.HelloWorldActivityImpl")
            .hasSourceEquivalentTo(expectedSource);
    }

}
