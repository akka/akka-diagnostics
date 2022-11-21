/**
 *  Copyright (C) 2015-2016 Lightbend <http://lightbend.com/>
 */
package com.lightbend.akka.diagnostics.mbean;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.PARAMETER;

/**
 * INTERNAL API
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ PARAMETER })
public @interface Name {
  String value();
}
