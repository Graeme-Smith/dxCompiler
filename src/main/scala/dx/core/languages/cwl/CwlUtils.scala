package dx.core.languages.cwl

import dx.translator.CallableAttributes.{DescriptionAttribute, TitleAttribute}
import dx.cwl.{ArrayValue, BooleanValue, CwlArray, CwlBoolean, CwlDirectory, CwlDouble, CwlEnum, CwlFile, CwlFloat, CwlInt, CwlLong, CwlNull, CwlOptional, CwlRecord, CwlSchema, CwlString, CwlType, CwlValue, DirectoryValue, DoubleValue, FileValue, FloatValue, IntValue, LongValue, NullValue, ObjectValue, SchemaDefRequirement, StringValue}
import dx.core.ir.{CallableAttribute, ParameterAttribute, Type, Value}
import dx.core.ir.Type.{TArray, TBoolean, TDirectory, TEnum, TFile, TFloat, THash, TInt, TOptional, TSchema, TString}
import dx.core.ir.Value.{VArray, VBoolean, VDirectory, VFile, VFloat, VHash, VInt, VNull, VString}
import dx.translator.ParameterAttributes.{HelpAttribute, LabelAttribute}
import wdlTools.syntax.SourceLocation

object CwlUtils {
  val locPlaceholder: SourceLocation = SourceLocation.empty

  def toIRType(cwlTypes: Vector[CwlType]): Type = {
    cwlTypes match {
      case _ if cwlTypes.size > 1 && cwlTypes.contains(CwlNull) => TOptional(toIRType(cwlTypes.filter(_ != CwlNull)))
      case _ if cwlTypes.length == 1 => cwlTypes.head match {
        case CwlBoolean => TBoolean
        case CwlInt => TInt
        case CwlLong => TInt
        case CwlDouble => TFloat
        case CwlFloat => TFloat
        case CwlString => TString
        case CwlFile => TFile
        case CwlDirectory => TDirectory
        case a: CwlArray => TSchema(a.name.get, Map[String, Type]("array" -> TArray(toIRType(a.itemTypes))))
        case r: CwlRecord => TSchema(r.name.get, r.fields.map({ case (key, value) => key -> toIRType(value.types) }))
        case e: CwlEnum => TSchema(e.name.get, Map[String, Type]("enum" -> TEnum(e.symbols)))
        case _ => throw new Exception(s"Cannot convert CWL type ${cwlTypes.head} to IR")
      }
      case _ if cwlTypes.isEmpty => throw new IllegalArgumentException("Vector of CwlTypes must be non-empty!")
      case other => throw new NotImplementedError(s"Multiple types are not supported yet! (${other} was given)")
    }
  }

  def fromIRType(irType: Type, typeAliases: Map[String, CwlSchema] = Map.empty): CwlType = {
    irType match {
      case TBoolean => CwlBoolean
      case TInt => CwlInt
      case TFloat => CwlFloat
      case TString => CwlString
      case TFile => CwlFile
      case TDirectory => CwlDirectory
      case TOptional(t) => fromIRType(t)
      case TSchema(name, _) =>
        throw new Exception(s"Unknown type ${name}")
      case _ =>
        throw new Exception(s"Cannot convert IR type ${irType} to CWL")
    }
  }


  def getDefaultIRValue(cwlTypes: Vector[CwlType]): Value = {
    getDefaultIRValue(toIRType(cwlTypes))
  }

  def getDefaultIRValue(IRType: Type): Value = {
    IRType match {
      case TInt => VInt(0)
      case TFloat => VFloat(0.0)
      case TBoolean => VBoolean(true)
      case TString => VString("")
      case TFile => VFile("placeholder.txt")
      case TOptional(t) => getDefaultIRValue(t)
      case TArray(_, _) => VArray(Vector.empty[Value])
      case TDirectory => VDirectory(".")
      case THash => VHash(Map.empty[String, Value])
      case TSchema(_, members) => VHash(members.map({ case (key, value) => key -> getDefaultIRValue(value) }))
      case other => throw new NotImplementedError(s"${other} is not supported.")
    }
  }

  def getDefaultCWLValue(cwlTypes: Vector[CwlType]): CwlValue = {
    val cwlType = cwlTypes.size match {
      case 1 => cwlTypes.head
      case 0 => throw new Exception("Variable in CWL has to have at least one possible type.")
      case _ => cwlTypes.find(_ != CwlNull).get // Cannot be empty, as there has to be at least 2 types and both cannot be CwlNull (two nulls in cwl file are parsed as one null)
    }
    cwlType match {
      case CwlInt => IntValue(0)
      case CwlFloat => FloatValue(0.0)
      case CwlDouble => DoubleValue(0.0)
      case CwlBoolean => BooleanValue(true)
      case CwlString => StringValue("")
      case CwlFile => FileValue("placeholder.txt")
      case CwlDirectory => DirectoryValue(".")
      case CwlOptional(cwlType) => getDefaultCWLValue(Vector(cwlType))
      case _: CwlArray => ArrayValue(Vector.empty[CwlValue])
      case e: CwlEnum => StringValue(e.symbols.toList.min)
      case r: CwlRecord => ObjectValue(r.fields.map({ case (key, value) => key -> getDefaultCWLValue(value.types) }))
      case other => throw new NotImplementedError(s"${other} is not supported.")
    }
  }

  def toIRValue(cwlValue: CwlValue): Value = {
    cwlValue match {
      case NullValue => VNull
      case BooleanValue(b) => VBoolean(value = b)
      case IntValue(i) => VInt(i)
      case LongValue(l) => VInt(l)
      case FloatValue(f) => VFloat(f)
      case DoubleValue(d) => VFloat(d)
      case StringValue(s) => VString(s)
      case ArrayValue(a) => VArray(a.map(toIRValue))
      case fileValue: FileValue => VFile(fileValue.location.get)
      case ObjectValue(m) => VHash(m.map({ case (key, value) => key -> toIRValue(value) }))
      case null => VNull
      case _ => throw new Exception(s"Invalid CWL value ${cwlValue})")
    }
  }


  def toIR(cwl: Map[String, (Vector[CwlType], Option[CwlValue])]): Map[String, (Type, Value)] = {
    cwl.map {
      case (name, (cwlTypes, cwlValue)) =>
        val irType = toIRType(cwlTypes)
        val irValue = cwlValue match {
          case Some(v) => toIRValue(v)
          case None => null
        }
        name -> (irType, irValue)
    }
  }

  def createParameterAttributes(doc: Option[String], label: Option[String]): Vector[ParameterAttribute] = {
    val helpAttribute: Option[HelpAttribute] = doc match {
      case Some(s: String) => Option(HelpAttribute(s))
      case None => Option.empty[HelpAttribute]
    }
    val labelAttribute: Option[LabelAttribute] = label match {
      case Some(s: String) => Option(LabelAttribute(s))
      case None => Option.empty[LabelAttribute]
    }
    Vector(helpAttribute, labelAttribute).collect { case Some(i: ParameterAttribute) => i }
  }

  def createCallableAttributes(doc: Option[String], label: Option[String]): Vector[CallableAttribute] = {
    val helpAttribute: Option[CallableAttribute] = doc match {
      case Some(s: String) => Option(DescriptionAttribute(s))
      case None => Option.empty[DescriptionAttribute]
    }
    val labelAttribute: Option[CallableAttribute] = label match {
      case Some(s: String) => Option(TitleAttribute(s))
      case None => Option.empty[TitleAttribute]
    }
    Vector(helpAttribute, labelAttribute).collect { case Some(i: CallableAttribute) => i }
  }

  def getTypeAliases(requirements: Vector[SchemaDefRequirement]): Map[String, CwlSchema] = {
    var typeAliases = Map.empty[String, CwlSchema]
    for (req <- requirements) {
      for (typedef <- req.typeDefinitions) {
        typeAliases = typeAliases ++ Map(typedef.name.get -> typedef)
      }
    }
    typeAliases
  }
}


