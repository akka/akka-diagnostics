/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm
import sbt._
import com.typesafe.sbt.SbtScalariform
import com.typesafe.sbt.SbtScalariform.ScalariformKeys

import scalariform.formatter.preferences._

object Formatting {
  lazy val formatSettings = SbtScalariform.scalariformSettings ++ Seq(
    ScalariformKeys.preferences in Compile  := formattingPreferences,
    ScalariformKeys.preferences in Test     := formattingPreferences,
    ScalariformKeys.preferences in MultiJvm := formattingPreferences
  )

  lazy val docFormatSettings = SbtScalariform.scalariformSettings ++ Seq(
    ScalariformKeys.preferences in Compile  := docFormattingPreferences,
    ScalariformKeys.preferences in Test     := docFormattingPreferences,
    ScalariformKeys.preferences in MultiJvm := docFormattingPreferences
  )

  def formattingPreferences = {
    FormattingPreferences()
      .setPreference(RewriteArrowSymbols, false)
      .setPreference(AlignParameters, true)
      .setPreference(AlignSingleLineCaseStatements, true)
      .setPreference(DanglingCloseParenthesis, Preserve)
      .setPreference(DoubleIndentClassDeclaration, false)
      .setPreference(SpacesAroundMultiImports, true)
  }

  def docFormattingPreferences = {
    FormattingPreferences()
      .setPreference(RewriteArrowSymbols, false)
      .setPreference(AlignParameters, true)
      .setPreference(AlignSingleLineCaseStatements, true)
      .setPreference(DanglingCloseParenthesis, Preserve)
      .setPreference(DoubleIndentClassDeclaration, false)
      .setPreference(SpacesAroundMultiImports, true)
  }
}