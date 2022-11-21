/**
 *  Copyright (C) 2015-2016 Lightbend <http://lightbend.com/>
 */
package com.lightbend.akka.diagnostics.mbean;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;

/**
 * INTERNAL API
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ CONSTRUCTOR, METHOD, PARAMETER, TYPE })
public @interface Description {
  String value();
}
