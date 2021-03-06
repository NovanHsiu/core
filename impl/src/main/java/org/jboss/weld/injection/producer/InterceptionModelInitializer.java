/*
 * JBoss, Home of Professional Open Source
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.weld.injection.producer;

import static org.jboss.weld.util.Interceptors.filterInterceptorBindings;
import static org.jboss.weld.util.Interceptors.flattenInterceptorBindings;
import static org.jboss.weld.util.Interceptors.mergeBeanInterceptorBindings;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.enterprise.inject.spi.AnnotatedConstructor;
import javax.enterprise.inject.spi.AnnotatedMethod;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.InterceptionType;
import javax.enterprise.inject.spi.Interceptor;
import javax.interceptor.ExcludeClassInterceptors;

import org.jboss.weld.annotated.enhanced.EnhancedAnnotatedType;
import org.jboss.weld.ejb.EJBApiAbstraction;
import org.jboss.weld.exceptions.DeploymentException;
import org.jboss.weld.interceptor.builder.InterceptionModelBuilder;
import org.jboss.weld.interceptor.builder.InterceptorsApiAbstraction;
import org.jboss.weld.interceptor.reader.InterceptorMetadataReader;
import org.jboss.weld.interceptor.reader.TargetClassInterceptorMetadata;
import org.jboss.weld.interceptor.spi.metadata.InterceptorClassMetadata;
import org.jboss.weld.interceptor.spi.model.InterceptionModel;
import org.jboss.weld.logging.BeanLogger;
import org.jboss.weld.logging.ValidatorLogger;
import org.jboss.weld.manager.BeanManagerImpl;
import org.jboss.weld.util.Beans;
import org.jboss.weld.util.reflection.Reflections;

import com.google.common.collect.Lists;

/**
 * Initializes {@link InterceptionModel} for a {@link Bean} or a non-contextual component.
 *
 * @author Marko Luksa
 * @author Jozef Hartinger
 *
 * @param <T>
 */
public class InterceptionModelInitializer<T> {

    public static <T> InterceptionModelInitializer<T> of(BeanManagerImpl manager, EnhancedAnnotatedType<T> annotatedType, Bean<?> bean) {
        return new InterceptionModelInitializer<T>(manager, annotatedType, Beans.getBeanConstructorStrict(annotatedType), bean);
    }

    private final BeanManagerImpl manager;
    private final InterceptorMetadataReader reader;
    private final EnhancedAnnotatedType<T> annotatedType;
    private final Set<Class<? extends Annotation>> stereotypes;
    private final AnnotatedConstructor<T> constructor;

    private final InterceptorsApiAbstraction interceptorsApi;
    private final EJBApiAbstraction ejbApi;

    private List<AnnotatedMethod<?>> businessMethods;
    private final InterceptionModelBuilder builder;
    private boolean hasSerializationOrInvocationInterceptorMethods;

    public InterceptionModelInitializer(BeanManagerImpl manager, EnhancedAnnotatedType<T> annotatedType, AnnotatedConstructor<T> constructor, Bean<?> bean) {
        this.constructor = constructor;
        this.manager = manager;
        this.reader = manager.getInterceptorMetadataReader();
        this.annotatedType = annotatedType;
        this.builder = new InterceptionModelBuilder();
        if (bean == null) {
            stereotypes = Collections.emptySet();
        } else {
            stereotypes = bean.getStereotypes();
        }
        this.interceptorsApi = manager.getServices().get(InterceptorsApiAbstraction.class);
        this.ejbApi = manager.getServices().get(EJBApiAbstraction.class);
    }

    public void init() {
        initTargetClassInterceptors();
        businessMethods = Beans.getInterceptableMethods(annotatedType);

        initEjbInterceptors();
        initCdiInterceptors();

        InterceptionModel interceptionModel = builder.build();
        if (interceptionModel.getAllInterceptors().size() > 0 || hasSerializationOrInvocationInterceptorMethods) {
            if (annotatedType.isFinal()) {
                throw BeanLogger.LOG.finalBeanClassWithInterceptorsNotAllowed(annotatedType.getJavaClass());
            }
            if (Reflections.isPrivate(constructor.getJavaMember())) {
                throw new DeploymentException(ValidatorLogger.LOG.notProxyablePrivateConstructor(annotatedType.getJavaClass(), constructor, annotatedType.getJavaClass()));
            }
            manager.getInterceptorModelRegistry().put(annotatedType.slim(), interceptionModel);
        }
    }

    private void initTargetClassInterceptors() {
        if (!Beans.isInterceptor(annotatedType)) {
            TargetClassInterceptorMetadata interceptorClassMetadata = reader.getTargetClassInterceptorMetadata(annotatedType);
            builder.setTargetClassInterceptorMetadata(interceptorClassMetadata);
            hasSerializationOrInvocationInterceptorMethods = interceptorClassMetadata.isEligible(org.jboss.weld.interceptor.spi.model.InterceptionType.AROUND_INVOKE)
                    || interceptorClassMetadata.isEligible(org.jboss.weld.interceptor.spi.model.InterceptionType.AROUND_TIMEOUT)
                    || interceptorClassMetadata.isEligible(org.jboss.weld.interceptor.spi.model.InterceptionType.PRE_PASSIVATE)
                    || interceptorClassMetadata.isEligible(org.jboss.weld.interceptor.spi.model.InterceptionType.POST_ACTIVATE);
        } else {
            // an interceptor does not have lifecycle methods of its own, but it intercepts the methods of the
            // target class
            hasSerializationOrInvocationInterceptorMethods = false;
        }
    }

    private void initCdiInterceptors() {
        Map<Class<? extends Annotation>, Annotation> classBindingAnnotations = getClassInterceptorBindings();
        initCdiLifecycleInterceptors(classBindingAnnotations);
        initCdiConstructorInterceptors(classBindingAnnotations);
        initCdiBusinessMethodInterceptors(classBindingAnnotations);
    }

    private Map<Class<? extends Annotation>, Annotation> getClassInterceptorBindings() {
        return mergeBeanInterceptorBindings(manager, annotatedType, stereotypes);
    }

    /*
     * CDI lifecycle interceptors
     */

    private void initCdiLifecycleInterceptors(Map<Class<? extends Annotation>, Annotation> classBindingAnnotations) {
        if (classBindingAnnotations.size() == 0) {
            return;
        }
        final Collection<Annotation> qualifiers = classBindingAnnotations.values();
        initLifeCycleInterceptor(InterceptionType.POST_CONSTRUCT, qualifiers);
        initLifeCycleInterceptor(InterceptionType.PRE_DESTROY, qualifiers);
        initLifeCycleInterceptor(InterceptionType.PRE_PASSIVATE, qualifiers);
        initLifeCycleInterceptor(InterceptionType.POST_ACTIVATE, qualifiers);
    }

    private void initLifeCycleInterceptor(InterceptionType interceptionType, Collection<Annotation> annotations) {
        List<Interceptor<?>> resolvedInterceptors = manager.resolveInterceptors(interceptionType, annotations);
        if (!resolvedInterceptors.isEmpty()) {
            builder.intercept(interceptionType, asInterceptorMetadata(resolvedInterceptors));
        }
    }

    /*
     * CDI business method interceptors
     */

    private void initCdiBusinessMethodInterceptors(Map<Class<? extends Annotation>, Annotation> classBindingAnnotations) {
        for (AnnotatedMethod<?> method : businessMethods) {
            initCdiBusinessMethodInterceptor(method, getMemberBindingAnnotations(classBindingAnnotations, method.getAnnotations()));
        }
    }

    private void initCdiBusinessMethodInterceptor(AnnotatedMethod<?> method, Collection<Annotation> methodBindingAnnotations) {
        if (methodBindingAnnotations.size() == 0) {
            return;
        }
        initInterceptor(InterceptionType.AROUND_INVOKE, method, methodBindingAnnotations);
        initInterceptor(InterceptionType.AROUND_TIMEOUT, method, methodBindingAnnotations);
    }

    private void initInterceptor(InterceptionType interceptionType, AnnotatedMethod<?> method, Collection<Annotation> methodBindingAnnotations) {
        List<Interceptor<?>> methodBoundInterceptors = manager.resolveInterceptors(interceptionType, methodBindingAnnotations);
        if (methodBoundInterceptors != null && methodBoundInterceptors.size() > 0) {
            if (Reflections.isFinal(method.getJavaMember())) {
                throw BeanLogger.LOG.finalInterceptedBeanMethodNotAllowed(method, methodBoundInterceptors.get(0).getBeanClass().getName());
            }
            Method javaMethod = method.getJavaMember();
            builder.intercept(interceptionType, javaMethod, asInterceptorMetadata(methodBoundInterceptors));
        }
    }

    /*
     * CDI @AroundConstruct interceptors
     */

    private void initCdiConstructorInterceptors(Map<Class<? extends Annotation>, Annotation> classBindingAnnotations) {
        Collection<Annotation> constructorBindings = getMemberBindingAnnotations(classBindingAnnotations, constructor.getAnnotations());
        if (constructorBindings.isEmpty()) {
            return;
        }
        initLifeCycleInterceptor(InterceptionType.AROUND_CONSTRUCT, constructorBindings);
    }

    private Collection<Annotation> getMemberBindingAnnotations(Map<Class<? extends Annotation>, Annotation> classBindingAnnotations, Set<Annotation> memberAnnotations) {
        Set<Annotation> methodBindingAnnotations = flattenInterceptorBindings(manager, filterInterceptorBindings(manager, memberAnnotations), true, true);
        return mergeMethodInterceptorBindings(classBindingAnnotations, methodBindingAnnotations).values();
    }

    /*
     * EJB-style interceptors
     */

    private void initEjbInterceptors() {
        initClassDeclaredEjbInterceptors();
        initConstructorDeclaredEjbInterceptors();
        for (AnnotatedMethod<?> method : businessMethods) {
            initMethodDeclaredEjbInterceptors(method);
        }
    }

    /*
     * Class-level EJB-style interceptors
     */
    private void initClassDeclaredEjbInterceptors() {
        Class<?>[] classDeclaredInterceptors = interceptorsApi.extractInterceptorClasses(annotatedType);
        boolean excludeClassLevelAroundConstructInterceptors = constructor.isAnnotationPresent(ExcludeClassInterceptors.class);

        if (classDeclaredInterceptors != null) {
            for (Class<?> clazz : classDeclaredInterceptors) {
                InterceptorClassMetadata<?> interceptor = reader.getPlainInterceptorMetadata(clazz);
                for (InterceptionType interceptionType : InterceptionType.values()) {
                    if (excludeClassLevelAroundConstructInterceptors && interceptionType.equals(InterceptionType.AROUND_CONSTRUCT)) {
                        /*
                         * @ExcludeClassInterceptors suppresses @AroundConstruct interceptors defined on class level
                         */
                        continue;
                    }
                    if (interceptor.isEligible(org.jboss.weld.interceptor.spi.model.InterceptionType.valueOf(interceptionType))) {
                        builder.intercept(interceptionType, Collections.<InterceptorClassMetadata<?>>singleton(interceptor));
                    }
                }
            }
        }
    }

    /*
     * Constructor-level EJB-style interceptors
     */
    public void initConstructorDeclaredEjbInterceptors() {
        Class<?>[] constructorDeclaredInterceptors = interceptorsApi.extractInterceptorClasses(constructor);
        if (constructorDeclaredInterceptors != null) {
            for (Class<?> clazz : constructorDeclaredInterceptors) {
                builder.intercept(InterceptionType.AROUND_CONSTRUCT, Collections.<InterceptorClassMetadata<?>>singleton(reader.getPlainInterceptorMetadata(clazz)));
            }
        }
    }

    private void initMethodDeclaredEjbInterceptors(AnnotatedMethod<?> method) {
        Method javaMethod = method.getJavaMember();

        boolean excludeClassInterceptors = method.isAnnotationPresent(interceptorsApi.getExcludeClassInterceptorsAnnotationClass());
        if (excludeClassInterceptors) {
            builder.addMethodIgnoringGlobalInterceptors(javaMethod);
        }

        Class<?>[] methodDeclaredInterceptors = interceptorsApi.extractInterceptorClasses(method);
        if (methodDeclaredInterceptors != null && methodDeclaredInterceptors.length > 0) {
            if (Reflections.isFinal(method.getJavaMember())) {
                throw new DeploymentException(BeanLogger.LOG.finalInterceptedBeanMethodNotAllowed(method, methodDeclaredInterceptors[0].getName()));
            }

            InterceptionType interceptionType = isTimeoutAnnotationPresentOn(method)
                    ? InterceptionType.AROUND_TIMEOUT
                    : InterceptionType.AROUND_INVOKE;
            builder.intercept(interceptionType, javaMethod, getMethodDeclaredInterceptorMetadatas(methodDeclaredInterceptors));
        }
    }

    private List<InterceptorClassMetadata<?>> getMethodDeclaredInterceptorMetadatas(Class<?>[] methodDeclaredInterceptors) {
        List<InterceptorClassMetadata<?>> list = Lists.newLinkedList();
        for (Class<?> clazz : methodDeclaredInterceptors) {
            list.add(reader.getPlainInterceptorMetadata(clazz));
        }
        return list;
    }

    private boolean isTimeoutAnnotationPresentOn(AnnotatedMethod<?> method) {
        return method.isAnnotationPresent(ejbApi.TIMEOUT_ANNOTATION_CLASS);
    }

    /**
     * Merges bean interceptor bindings (including inherited ones) with method interceptor bindings. Method interceptor bindings
     * override bean interceptor bindings. The bean binding map is not modified - a copy is used.
     */
    protected Map<Class<? extends Annotation>, Annotation> mergeMethodInterceptorBindings(Map<Class<? extends Annotation>, Annotation> beanBindings,
            Collection<Annotation> methodBindingAnnotations) {

        Map<Class<? extends Annotation>, Annotation> mergedBeanBindings = new HashMap<Class<? extends Annotation>, Annotation>(beanBindings);
        // conflict detection
        Set<Class<? extends Annotation>> processedBindingTypes = new HashSet<Class<? extends Annotation>>();

        for (Annotation methodBinding : methodBindingAnnotations) {
            Class<? extends Annotation> methodBindingType = methodBinding.annotationType();
            if (processedBindingTypes.contains(methodBindingType)) {
                throw new DeploymentException(BeanLogger.LOG.conflictingInterceptorBindings(annotatedType));
            }
            processedBindingTypes.add(methodBindingType);
            // override bean interceptor binding
            mergedBeanBindings.put(methodBindingType, methodBinding);
        }
        return mergedBeanBindings;
    }

    private List<InterceptorClassMetadata<?>> asInterceptorMetadata(List<Interceptor<?>> interceptors) {
        return Lists.transform(interceptors, reader.getInterceptorToInterceptorMetadataFunction());
    }
}
