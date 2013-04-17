/*
 * JBoss, Home of Professional Open Source
 * Copyright 2013, Red Hat, Inc., and individual contributors
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
package org.jboss.weld.tests.ejb.interceptor.serializable;

import java.io.IOException;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.BeanArchive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.weld.ejb.SessionBeanInterceptor;
import org.jboss.weld.test.util.Utils;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * {@link SessionBeanInterceptor} is a part of the integrator contract. Therefore, we should not break it!
 *
 * @author Jozef Hartinger
 *
 */
@RunWith(Arquillian.class)
public class SessionBeanInterceptorTrulySerializableTest {

    @Deployment
    public static Archive<?> getDeployment() {
        return ShrinkWrap.create(BeanArchive.class).addPackage(SessionBeanInterceptorTrulySerializableTest.class.getPackage())
                .addClass(Utils.class);
    }

    @Test
    public void testSessionBeanInterceptorTrulySerializable() throws ClassNotFoundException, IOException {
        SessionBeanInterceptor interceptor = new SessionBeanInterceptor();
        Utils.deserialize(Utils.serialize(interceptor));
    }
}
