package com.bennyhuo.tieguanyin.compiler.fragment.methods;

import com.bennyhuo.tieguanyin.compiler.basic.RequiredField;
import com.bennyhuo.tieguanyin.compiler.fragment.FragmentClass;
import com.bennyhuo.tieguanyin.compiler.shared.SharedElementEntity;
import com.bennyhuo.tieguanyin.compiler.utils.JavaTypes;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import java.util.ArrayList;

import javax.lang.model.element.Modifier;

/**
 * Created by benny on 1/31/18.
 */

public class ShowMethod {

    private FragmentClass fragmentClass;
    private String name;
    private ArrayList<RequiredField> requiredFields = new ArrayList<>();
    private boolean isStaticMethod = true;

    public ShowMethod(FragmentClass fragmentClass, String name) {
        this.fragmentClass = fragmentClass;
        this.name = name;
    }

    public ShowMethod staticMethod(boolean staticMethod) {
        isStaticMethod = staticMethod;
        return this;
    }

    public void visitField(RequiredField binding) {
        requiredFields.add(binding);
    }

    public void setName(String name) {
        this.name = name;
    }

    public ShowMethod copy(String name) {
        ShowMethod openMethod = new ShowMethod(fragmentClass, name);
        for (RequiredField visitedBinding : requiredFields) {
            openMethod.visitField(visitedBinding);
        }
        return openMethod;
    }

    public void build(TypeSpec.Builder typeBuilder){
        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(name)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addParameter(JavaTypes.ACTIVITY, "activity")
                .addParameter(int.class, "containerId")
                .beginControlFlow("if(activity instanceof $T)", JavaTypes.SUPPORT_ACTIVITY)
                .addStatement("$T.INSTANCE.init(activity)", JavaTypes.ACTIVITY_BUILDER);

        methodBuilder.addStatement("$T intent = new $T()", JavaTypes.INTENT, JavaTypes.INTENT);

        for (RequiredField requiredField : requiredFields) {
            String name = requiredField.getName();
            methodBuilder.addParameter(requiredField.asTypeName(), name);
            methodBuilder.addStatement("intent.putExtra($S, $L)", name, name);
        }

        if(isStaticMethod){
            methodBuilder.addModifiers(Modifier.STATIC);
        } else {
            //非静态则需要填充 optional 成员
            methodBuilder.addStatement("fillIntent(intent)");
        }

        ArrayList<SharedElementEntity> sharedElements = fragmentClass.getSharedElementsRecursively();
        if(sharedElements.isEmpty()){
            methodBuilder.addStatement("$T.showFragment(($T) activity, containerId, intent.getExtras(), $T.class, null)", JavaTypes.FRAGMENT_BUILDER,  JavaTypes.SUPPORT_ACTIVITY, fragmentClass.getType());
        } else {
            methodBuilder.addStatement("$T<$T<$T, $T>> sharedElements = new $T<>()", JavaTypes.ARRAY_LIST, JavaTypes.SUPPORT_PAIR, String.class, String.class, JavaTypes.ARRAY_LIST)
                    .addStatement("$T container = activity.findViewById(containerId)", JavaTypes.VIEW);
            for (SharedElementEntity sharedElement : sharedElements) {
                if(sharedElement.sourceId == 0){
                    methodBuilder.addStatement("sharedElements.add(new Pair<>($S, $S))", sharedElement.sourceName, sharedElement.targetName);
                } else {
                    methodBuilder.addStatement("sharedElements.add(new Pair<>($T.getTransitionName(container.findViewById($L)), $S))", JavaTypes.VIEW_COMPAT, sharedElement.sourceId, sharedElement.targetName);
                }
            }
            methodBuilder.addStatement("$T.showFragment(($T) activity, containerId, intent.getExtras(), $T.class, sharedElements)", JavaTypes.FRAGMENT_BUILDER,  JavaTypes.SUPPORT_ACTIVITY, fragmentClass.getType());
        }
        methodBuilder.endControlFlow();

        typeBuilder.addMethod(methodBuilder.build());
    }
}
