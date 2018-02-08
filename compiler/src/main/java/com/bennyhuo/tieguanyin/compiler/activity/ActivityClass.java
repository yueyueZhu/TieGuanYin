package com.bennyhuo.tieguanyin.compiler.activity;

import com.bennyhuo.tieguanyin.annotations.ActivityBuilder;
import com.bennyhuo.tieguanyin.annotations.GenerateMode;
import com.bennyhuo.tieguanyin.annotations.ResultEntity;
import com.bennyhuo.tieguanyin.compiler.basic.RequiredField;
import com.bennyhuo.tieguanyin.compiler.result.ActivityResultClass;
import com.bennyhuo.tieguanyin.compiler.utils.TypeUtils;
import com.bennyhuo.tieguanyin.compiler.utils.Utils;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import com.squareup.kotlinpoet.FileSpec;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.processing.Filer;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

import kotlin.Metadata;

/**
 * Created by benny on 1/29/18.
 */

public class ActivityClass {
    private static final String METHOD_NAME = "start";
    private static final String METHOD_NAME_NO_OPTIONAL = METHOD_NAME + "WithoutOptional";
    private static final String METHOD_NAME_FOR_OPTIONAL = METHOD_NAME + "WithOptional";
    private static final String METHOD_NAME_SEPARATOR = "And";
    private static final String EXT_FUN_NAME_PREFIX = METHOD_NAME;
    private static final String POSIX = "Builder";

    private static final String CONSTS_REQUIRED_FIELD_PREFIX = "REQUIRED_";
    private static final String CONSTS_OPTIONAL_FIELD_PREFIX = "OPTIONAL_";
    private static final String CONSTS_RESULT_PREFIX = "RESULT_";

    private TypeElement type;
    private TreeSet<RequiredField> optionalFields = new TreeSet<>();
    private TreeSet<RequiredField> requiredFields = new TreeSet<>();
    private ActivityResultClass activityResultClass;
    private GenerateMode generateMode;

    public final String simpleName;
    public final String packageName;

    public ActivityClass(TypeElement type) {
        this.type = type;
        simpleName = TypeUtils.simpleName(type.asType());
        packageName = TypeUtils.getPackageName(type);

        Metadata metadata = type.getAnnotation(Metadata.class);
        //如果有这个注解，说明就是 Kotlin 类。
        boolean isKotlin = metadata != null;

        ActivityBuilder generateBuilder = type.getAnnotation(ActivityBuilder.class);
        if(generateBuilder.resultTypes().length > 0){
            activityResultClass = new ActivityResultClass(this, generateBuilder.resultTypes());
        }
        generateMode = generateBuilder.mode();
        if(generateMode == GenerateMode.Auto){
            if(isKotlin) generateMode = GenerateMode.Both;
            else generateMode = GenerateMode.JavaOnly;
        }
    }

    public void addSymbol(RequiredField field) {
        if (field.isRequired()) {
            requiredFields.add(field);
        } else {
            optionalFields.add(field);
        }
    }

    public Set<RequiredField> getRequiredFields() {
        return requiredFields;
    }

    public Set<RequiredField> getOptionalFields() {
        return optionalFields;
    }

    public TypeElement getType() {
        return type;
    }

    private void buildConstants(TypeSpec.Builder typeBuilder){
        for (RequiredField field : requiredFields) {
            typeBuilder.addField(FieldSpec.builder(String.class,
                    CONSTS_REQUIRED_FIELD_PREFIX + Utils.camelToUnderline(field.getName()),
                    Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$S", field.getName())
                    .build());
        }
        for (RequiredField field : optionalFields) {
            typeBuilder.addField(FieldSpec.builder(String.class,
                    CONSTS_OPTIONAL_FIELD_PREFIX + Utils.camelToUnderline(field.getName()),
                    Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$S", field.getName())
                    .build());
        }
        if(activityResultClass != null){
            for (ResultEntity resultEntity : activityResultClass.getResultEntities()) {
                typeBuilder.addField(FieldSpec.builder(String.class,
                        CONSTS_RESULT_PREFIX + Utils.camelToUnderline(resultEntity.name()),
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$S", resultEntity.name())
                        .build());
            }
        }
    }

    public void buildStartMethod(TypeSpec.Builder typeBuilder) {
        StartMethod startMethod = new StartMethod(this, METHOD_NAME);
        for (RequiredField field : getRequiredFields()) {
            startMethod.visitField(field);
        }

        StartMethod startMethodNoOptional = startMethod.copy(METHOD_NAME_NO_OPTIONAL);

        for (RequiredField field : getOptionalFields()) {
            startMethod.visitField(field);
        }
        startMethod.endWithResult(activityResultClass);
        typeBuilder.addMethod(startMethod.build());

        ArrayList<RequiredField> optionalBindings = new ArrayList<>(getOptionalFields());
        int size = optionalBindings.size();
        //选择长度为 i 的参数列表
        for (int step = 1; step < size; step++) {
            for (int start = 0; start < size; start++) {
                ArrayList<String> names = new ArrayList<>();
                StartMethod method = startMethodNoOptional.copy(METHOD_NAME_FOR_OPTIONAL);
                for(int index = start; index < step + start; index++){
                    RequiredField binding = optionalBindings.get(index % size);
                    method.visitField(binding);
                    names.add(Utils.capitalize(binding.getName()));
                }
                method.endWithResult(activityResultClass);
                method.renameTo(METHOD_NAME_FOR_OPTIONAL + Utils.joinString(names, METHOD_NAME_SEPARATOR));
                typeBuilder.addMethod(method.build());
            }
        }

        if (size > 0) {
            startMethodNoOptional.endWithResult(activityResultClass);
            typeBuilder.addMethod(startMethodNoOptional.build());
        }
    }

    public void buildInjectMethod(TypeSpec.Builder typeBuilder) {
        InjectMethod injectMethod = new InjectMethod(this);

        for (RequiredField field : getRequiredFields()) {
            injectMethod.visitField(field);
        }

        for (RequiredField field : getOptionalFields()) {
            injectMethod.visitField(field);
        }
        injectMethod.end();

        typeBuilder.addMethod(injectMethod.build());
    }

    public void buildStartFunKt(FileSpec.Builder fileSpecBuilder) {
        StartFunctionKt startMethodKt = new StartFunctionKt(this, simpleName + POSIX, EXT_FUN_NAME_PREFIX + simpleName);

        for (RequiredField field : getRequiredFields()) {
            startMethodKt.visitField(field);
        }

        for (RequiredField field : getOptionalFields()) {
            startMethodKt.visitField(field);
        }

        startMethodKt.endWithResult(activityResultClass);
        fileSpecBuilder.addFunction(startMethodKt.buildForContext());
        fileSpecBuilder.addFunction(startMethodKt.buildForView());
        fileSpecBuilder.addFunction(startMethodKt.buildForFragment());
    }

    public void brew(Filer filer) {
        TypeSpec.Builder typeBuilder = TypeSpec.classBuilder(simpleName + POSIX)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL);

        buildConstants(typeBuilder);

        buildInjectMethod(typeBuilder);

        if(activityResultClass != null){
            typeBuilder.addType(activityResultClass.buildOnActivityResultListenerInterface());
        }

        switch (generateMode) {
            case JavaOnly:
                buildStartMethod(typeBuilder);
                if(activityResultClass != null){
                    typeBuilder.addMethod(activityResultClass.buildFinishWithResultMethod());
                }
                break;
            case Both:
                buildStartMethod(typeBuilder);
                if(activityResultClass != null){
                    typeBuilder.addMethod(activityResultClass.buildFinishWithResultMethod());
                }
            case KotlinOnly:
                //region kotlin
                FileSpec.Builder fileSpecBuilder = FileSpec.builder(packageName, simpleName + POSIX);
                buildStartFunKt(fileSpecBuilder);
                if (activityResultClass != null) {
                    fileSpecBuilder.addFunction(activityResultClass.buildFinishWithResultKt());
                }
                writeKotlinToFile(filer, fileSpecBuilder.build());
                //endregion
                break;
        }

        writeJavaToFile(filer, typeBuilder.build());
    }

    private void writeJavaToFile(Filer filer, TypeSpec typeSpec){
        try {
            JavaFile file = JavaFile.builder(packageName, typeSpec).build();
            file.writeTo(filer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void writeKotlinToFile(Filer filer, FileSpec fileSpec){
        try {
            FileObject fileObject = filer.createResource(StandardLocation.SOURCE_OUTPUT, packageName, fileSpec.getName() + ".kt");
            Writer writer = fileObject.openWriter();
            fileSpec.writeTo(writer);
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}