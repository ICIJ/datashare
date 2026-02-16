package org.icij.datashare.asynctasks.temporal;

import static java.lang.Character.toUpperCase;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.joining;
import static org.apache.commons.lang3.ClassUtils.getPackageName;
import static org.icij.datashare.LambdaExceptionUtils.rethrowConsumer;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.MirroredTypesException;
import javax.lang.model.type.TypeMirror;
import javax.tools.JavaFileObject;
import org.icij.datashare.asynctasks.TaskFactory;

@SupportedAnnotationTypes("org.icij.datashare.asynctasks.temporal.TemporalSingleActivityWorkflow")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class TemporalWorkflowGenerator extends AbstractProcessor {
    private static final ClassName CLASS_TYPE = ClassName.get("java.lang", "Class");
    private static final ClassName EXCEPTION_TYPE = ClassName.get("java.lang", "Exception");
    private static final ParameterizedTypeName RETRIABLES_TYPE = ParameterizedTypeName.get(ClassName.get("java.util", "Set"),
        ParameterizedTypeName.get(CLASS_TYPE, WildcardTypeName.subtypeOf(EXCEPTION_TYPE)));
    private static final ClassName TEMPORAL_WF_IMPL_TYPE = ClassName.get(TemporalWorkflowImpl.class);
    private static final ClassName TEMPORAL_ACTIVITY_IMPL_TYPE = ClassName.get(TemporalActivityImpl.class);
    private static final ClassName WF_TYPE = ClassName.get(Workflow.class);
    private static final ClassName ACT_OPTIONS_TYPE = ClassName.get(ActivityOptions.class);
    private static final ClassName DURATION_TYPE = ClassName.get(Duration.class);
    private static final String WF_IMPL_CONSTRUCTOR_CODE = "this.activity = $T.newActivityStub($T.class, $T.newBuilder().setTaskQueue(\"$L\").setStartToCloseTimeout($L.parse(\"$L\")).build());";
    private static final ParameterizedTypeName ARG_TYPE = ParameterizedTypeName.get(
        ClassName.get("java.util", "Map"),
        ClassName.get("java.lang", "String"),
        ClassName.get("java.lang", "Object")
    );

    public TemporalWorkflowGenerator() {
        super();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(TemporalSingleActivityWorkflow.class)) {
            TypeElement classElement = (TypeElement) element;
            TemporalSingleActivityWorkflow annotation = classElement.getAnnotation(TemporalSingleActivityWorkflow.class);
            String packageName = getPackageName(classElement.getQualifiedName().toString());

            String wfName = annotation.name();
            String baseName = asClassName(wfName, "-");
            String wfInterface = baseName + "Workflow";
            String wfImpl = wfInterface + "Impl";
            String actInterface = baseName + "Activity";
            String actImpl = actInterface + "Impl";
            TypeName outputType = parseOutputType(classElement);
            ClassName datashareTaskType = ClassName.get(getPackageName(classElement.getQualifiedName().toString()), classElement.getSimpleName().toString());
            String actTimeout = annotation.activityOptions().timeout();
            String actTaskQueue = annotation.activityOptions().taskQueue();
            Set<TypeName> retriables = parseRetriables(annotation.activityOptions());


            Map<String, JavaFile> generated = Map.of(
                wfInterface, generateWorkflowInterface(packageName, wfInterface, wfName, outputType),
                actInterface, generateActivity(packageName, actInterface, wfName, outputType),
                wfImpl, generateWorkflowImpl(packageName, wfInterface, outputType, actInterface, actTaskQueue, actTimeout),
                actImpl, generateActivityImpl(packageName, actInterface, outputType, datashareTaskType, retriables)
            );

            try {
                generated.entrySet().forEach(rethrowConsumer( e -> {
                    JavaFileObject activity = processingEnv.getFiler().createSourceFile(packageName + "." + e.getKey());
                    try (PrintWriter out = new PrintWriter(activity.openWriter())) {
                        e.getValue().writeTo(out);
                    }
                }));
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }

        }
        return true;
    }

    private TypeName parseOutputType(TypeElement classElement) {
        for (Element enclosed : classElement.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.METHOD) {
                ExecutableElement method = (ExecutableElement) enclosed;
                if (method.getSimpleName().toString().equals("call") && method.getParameters().isEmpty()) {
                    return TypeName.get(method.getReturnType());
                }
            }
        }
        throw new RuntimeException("failed to find call() method on " + classElement);
    }

    private static Set<TypeName> parseRetriables(ActivityOpts activityOptions) {
        List<? extends TypeMirror> retriableTypes;
        try {
            activityOptions.retriables();
            return Set.of();
        } catch (MirroredTypesException ex) {
            retriableTypes = ex.getTypeMirrors();
        }
        return retriableTypes.stream().map(TypeName::get).collect(Collectors.toSet());
    }

    private JavaFile generateWorkflowInterface(String packageName, String wfInterface, String wfName, TypeName outputType) {
        AnnotationSpec wfMethodAnnotationSpec = AnnotationSpec.builder(WorkflowMethod.class)
            .addMember("name", "$S", wfName)
            .build();
        MethodSpec run = MethodSpec.methodBuilder("run")
            .addAnnotation(wfMethodAnnotationSpec)
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addParameter(ParameterSpec.builder(ARG_TYPE, "args", Modifier.FINAL).build())
            .returns(outputType)
            .addException(Exception.class)
            .build();
        TypeSpec wfInterfaceSpec = TypeSpec.interfaceBuilder(wfInterface)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(WorkflowInterface.class)
            .addMethod(run)
            .build();
        return JavaFile.builder(packageName, wfInterfaceSpec).build();
    }

    private JavaFile generateWorkflowImpl(String packageName, String wfInterface, TypeName outputType, String actInterface, String actTaskQueue, String actTimeout) {
        String workflow = wfInterface + "Impl";
        ClassName actInterfaceType = ClassName.get(packageName, actInterface);
        MethodSpec constructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addCode(WF_IMPL_CONSTRUCTOR_CODE, WF_TYPE, actInterfaceType, ACT_OPTIONS_TYPE, actTaskQueue, DURATION_TYPE, actTimeout)
            .build();
        MethodSpec run = MethodSpec.methodBuilder("run")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .addParameter(ParameterSpec.builder(ARG_TYPE, "args", Modifier.FINAL).build())
            .addCode("return this.activity.run(args);")
            .returns(outputType)
            .addException(Exception.class)
            .build();
        TypeSpec wfImplSpec = TypeSpec.classBuilder(workflow)
            .addModifiers(Modifier.PUBLIC)
            .superclass(TEMPORAL_WF_IMPL_TYPE)
            .addSuperinterface(ClassName.get(packageName, wfInterface))
            .addField(FieldSpec.builder(actInterfaceType, "activity", Modifier.PRIVATE, Modifier.FINAL).build())
            .addMethod(constructor)
            .addMethod(run)
            .build();
        return JavaFile.builder(packageName, wfImplSpec).build();

    }

    private JavaFile generateActivity(
        String packageName, String actInterface, String actName, TypeName outputType
    ) {
        AnnotationSpec actMethodAnnotationSpec = AnnotationSpec.builder(ActivityMethod.class)
            .addMember("name", "$S", actName)
            .build();
        MethodSpec run = MethodSpec.methodBuilder("run")
            .addAnnotation(actMethodAnnotationSpec)
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addParameter(ParameterSpec.builder(ARG_TYPE, "args", Modifier.FINAL).build())
            .returns(outputType)
            .addException(Exception.class)
            .build();
        TypeSpec actInterfaceSpec = TypeSpec.interfaceBuilder(actInterface)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(ActivityInterface.class)
            .addMethod(run)
            .build();
        return JavaFile.builder(packageName, actInterfaceSpec).build();
    }

    private JavaFile generateActivityImpl(String packageName, String actInterface, TypeName outputType, TypeName datashareTaskClass, Set<TypeName> retriables) {
        String activityImpl = actInterface + "Impl";
        MethodSpec constructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(TaskFactory.class, "factory")
            .addParameter(WorkflowClient.class, "client")
            .addParameter(Double.class, "progressWeight")
            .addCode("super(factory, client, progressWeight);")
            .build();
        MethodSpec run = MethodSpec.methodBuilder("run")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .addParameter(ParameterSpec.builder(ARG_TYPE, "args", Modifier.FINAL).build())
            .addCode("return super.run(args);")
            .returns(outputType)
            .addException(Exception.class)
            .build();
        MethodSpec getTaskClass = MethodSpec.methodBuilder("getTaskClass")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PROTECTED)
            .addCode("return $T.class;", datashareTaskClass)
            .returns(ParameterizedTypeName.get(CLASS_TYPE, datashareTaskClass))
            .build();
        TypeSpec.Builder activityBuilder = TypeSpec.classBuilder(activityImpl)
            .superclass(ParameterizedTypeName.get(TEMPORAL_ACTIVITY_IMPL_TYPE, outputType, datashareTaskClass))
            .addSuperinterface(ClassName.get(packageName, actInterface))
            .addMethod(constructor)
            .addMethod(getTaskClass);
        if (!retriables.isEmpty()) {
            String setOfRetriables = "Set.of(";
            setOfRetriables += retriables.stream().map(e -> "$T.class").collect(joining(", "));
            setOfRetriables += ")";
            MethodSpec getRetriables = MethodSpec.methodBuilder("getRetriables")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .addCode("return " + setOfRetriables + ";", retriables.toArray())
                .returns(RETRIABLES_TYPE)
                .build();
            activityBuilder.addMethod(getRetriables);
        }
        TypeSpec workflowImplSpec = activityBuilder
            .addMethod(run)
            .build();
        return JavaFile.builder(packageName, workflowImplSpec).build();

    }

    private String asClassName(String name, String sep) {
        return stream(name.toLowerCase().split(sep)).map(s -> toUpperCase(s.charAt(0)) + s.substring(1))
            .collect(joining());
    }

}
