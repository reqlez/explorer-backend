package org.ergoplatform.explorer.protocol

import io.circe.Json
import io.circe.syntax._
import org.ergoplatform.explorer.protocol.models.{ExpandedRegister, RegisterValue}
import org.ergoplatform.explorer.{HexString, RegisterId}

import scala.util.Try

object registers {

  /** Expand registers into `register_id -> expanded_register` form.
    */
  @inline def expand(registers: Map[RegisterId, HexString]): Map[RegisterId, ExpandedRegister] = {
    val expanded =
      for {
        (idSig, serializedValue)        <- registers.toList
        RegisterValue(valueType, value) <- RegistersParser[Try].parseAny(serializedValue).toOption
      } yield idSig -> ExpandedRegister(serializedValue, valueType, value)
    expanded.toMap
  }

  /** Convolve registers into `register_id -> raw_value` form.
    */
  @inline def convolveJson(expanded: Json): Json =
    expanded
      .as[Map[RegisterId, ExpandedRegister]]
      .map(_.mapValues(_.serializedValue).asJson)
      .fold(_ => expanded, identity)
}
