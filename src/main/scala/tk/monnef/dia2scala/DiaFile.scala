package tk.monnef.dia2scala

import tk.monnef.dia2scala.DiaEntityType
import tk.monnef.dia2scala.DiaEntityType.DiaEntityType
import tk.monnef.dia2scala.DiaVisibility.DiaVisibility

import scala.annotation.tailrec
import scalaz.syntax.either._
import scalaz.syntax.optional._
import scalaz.syntax.std.all._
import scalaz.Scalaz.ToIdOps
import scalaz.{\/-, -\/, \/}

case class DiaFile(packages: Seq[DiaPackage], entities: Seq[DiaEntity], idToClass: Map[String, DiaEntity], importTable: ImportTable) {

  import DiaFile._

  def validationErrors(): Seq[String] = {
    entities.flatMap(_.validationErrors()) ++
      validatePackages(this) /* ++
      validateClassReferences(this)*/
    // TODO: uncomment after validation is done
  }

  def findEntityInAnyPackage(name: String): Seq[DiaEntity] = {
    entities.filter(c => c.ref.name == name)
  }

  def findEntity(fullName: String): Seq[DiaEntity] = {
    findEntity(DiaClassRefBase.createUncheckedUserClassRef(fullName))
  }

  def findEntity(inPackage: String, name: String): Seq[DiaEntity] = {
    findEntity(DiaUserClassRef(name, inPackage))
  }

  def findEntity(ref: DiaUserClassRef): Seq[DiaEntity] = {
    entities.filter(c => c.ref == ref)
  }

  def findObject(fullName: String): Seq[DiaEntity] = {
    findObject(DiaClassRefBase.createUncheckedUserClassRef(fullName))
  }

  def findObject(ref: DiaUserClassRef): Seq[DiaEntity] = {
    findEntity(ref, DiaEntityType.Object)
  }

  def findClass(ref: DiaUserClassRef): Seq[DiaEntity] = {
    findEntity(ref, DiaEntityType.Class)
  }

  def findClassInScalaSense(fullName: String): Seq[DiaEntity] = {
    findClassInScalaSense(DiaClassRefBase.createUncheckedUserClassRef(fullName))
  }

  def findClassInScalaSense(ref: DiaUserClassRef): Seq[DiaEntity] = {
    findEntity(ref, DiaEntityType.Class) ++ findEntity(ref, DiaEntityType.Enumeration) ++ findEntity(ref, DiaEntityType.Trait)
  }

  def findEntity(ref: DiaUserClassRef, entType: DiaEntityType): Seq[DiaEntity] = {
    findEntity(ref).filter(_.classType == entType)
  }

  def findEntity(fullName: String, entType: DiaEntityType): Seq[DiaEntity] = {
    findEntity(fullName).filter(_.classType == entType)
  }

  def entityExists(ref: DiaUserClassRef): Boolean = findEntity(ref).nonEmpty

  def fullNameFromId(id: String): String = idToClass.get(id) match {
    case Some(c) => c.ref.fullName
    case None => s"<<no class found for id '$id'>>"
  }

  def isEnumeration(fullName: String): Boolean = findEntity(fullName) |> { d => d.exists(_.classType == DiaEntityType.Enumeration)}

  def mapOperationsAndAttributesTypes(func: DiaClassRefBase => DiaClassRefBase): DiaFile = {
    copy(
      entities = entities.map { case entity =>
        entity.copy(
          operations = entity.operations.map(op =>
            op.copy(parameters = op.parameters.map { case param =>
              param.copy(pType = param.pType.map(func))
            })
          ),
          attributes = entity.attributes.map { case attr =>
            attr.copy(aType = attr.aType.map(func))
          }
        )
      }
    )
  }
}

object DiaFile {
  def apply(): DiaFile = DiaFile(Seq(), Seq(), Map(), ImportTable.default)

  def generateSeqIfTrue[T](cond: Boolean, msg: T): Seq[T] = if (cond) Seq(msg) else Seq()

  private final val emptySeqSeqString = Seq[Seq[String]]()

  def validatePackages(f: DiaFile): Seq[String] = {
    (
      ({
        val d = f.packages diff f.packages.distinct
        if (d.nonEmpty) Seq(Seq(s"Duplicate packages detected (${d.mkString(", ")})."))
        else Seq()
      }: Seq[Seq[String]]) ++ {
        val ep = f.packages.filter(p => !f.entities.exists(c => c.ref.inPackage == p.name))
        if (ep.nonEmpty) Seq(Seq(s"Empty packages detected (${ep.mkString(", ")})."))
        else Seq()
      }: Seq[Seq[String]]
      ).flatten
  }

  /*
  // TODO: validation
  def validateClassReferences(f: DiaFile): Seq[String] = {
    def validateClass(ref: DiaClassRefBase, f: DiaFile): Option[String] = {
      ref match {
        case DiaScalaClassRef(sc) => if (isScalaClass(sc)) None else "Not a Scala class.".some
        case uc: DiaUserClassRef => if (f.classExists(uc)) None else s"User class '${uc.fullName}' not found.".some
        case DiaGenericClassRef(b, pars) =>
          validateClass(b, f) match {
            case Some(err) => s"Error in generic type ${b.fullName}: $err".some
            case None =>
              val errs = pars.map(validateClass(_, f)).filter(_.isDefined).map(_.get)
              if (errs.isEmpty) None
              else s"Errors found in parameters of generic type ${b.fullName}:\n${errs.mkString("[", "\n", "]")}".some
          }
      }
    }
    def validateClassSeq(ref: DiaClassRefBase, f: DiaFile, errMsg: String): Seq[String] = {
      validateClass(ref, f) match {
        case Some(err) => Seq(errMsg +)
      }
    }
    (
      ({
        val ur = f.classes.filter(c => c.extendsFrom.isDefined && !f.classExists(c.extendsFrom.get))
        if (ur.nonEmpty) Seq(Seq(s"Found class(es) extending non-existent classes (${ur.map(c => c.ref.name + " extending " + c.extendsFrom.get.name).mkString(", ")}})."))
        else Seq()
      }: Seq[Seq[String]]) ++ ({
        val mt = f.classes.map(c => {
          val missingTraits = c.mixins.filter(m => !f.classExists(m))
          c -> missingTraits
        }).filter(_._2.nonEmpty)
        if (mt.nonEmpty) Seq(Seq(s"Found class(es) mixing in non-existent traits (${mt.map { case (c, ms) => c.ref.name + " using " + ms.map(_.name).mkString(", ")}.mkString("; ")}})."))
        else emptySeqSeqString
      }: Seq[Seq[String]]) ++ ({
        f.classes.map(c => {
          c.attributes.filter(a => a.aType.nonEmpty).flatMap(a => {
            a.aType.get match {
              case DiaScalaClassRef(sc) => if (isScalaClass(sc)) Seq() else Seq(s"Type '$sc' of attribute '${a.name}' in '${c.ref.name}' is not a Scala class.")
              case DiaClass \/- (ref) => if (f.classExists(ref)) Seq() else Seq(s"Class '${ref.name}' of attribute '${a.name}' in '${c.ref.name}' not found.")
            }
          })
        })
      }: Seq[Seq[String]]) ++ ({
        for {
          c <- f.classes
          o <- c.operations
          if o.oType.isDefined
          t = o.oType.get
        } yield t match {
          case -\/(sc) => if (isScalaClass(sc)) Seq() else Seq(s"Return type $sc of operations ${o.name} in ${c.ref.name} is not a Scala class.")
          case \/-(ref) => if (f.classExists(ref)) Seq() else Seq(s"Return type ${ref.name} of operation ${o.name} in ${c.ref.name} not found.")
        }
      }: Seq[Seq[String]]) ++ {
        (for {
          c <- f.classes
          o <- c.operations
          p <- o.parameters
          if p.pType.isDefined
          t = p.pType.get
        } yield t match {
            case -\/(sc) => if (isScalaClass(sc)) Seq() else Seq(s"Type '$sc' of parameter '${p.name}' of operation '${o.name}' in '${c.ref.name}' is not a Scala class.")
            case \/-(ref) =>
              if (ref.name.contains("[")) Seq() //TODO: implement generics checking
              else if (f.classExists(ref)) Seq() else Seq(s"Type '${ref.name}' of parameter '${p.name}' of operation '${o.name}' in '${c.ref.name}' not found.")
          }): Seq[Seq[String]]
      }
      ).flatten
  }
  */
}

trait Checkable {
  def validationErrors(): Seq[String]
}

case class DiaPackage(name: String, geometry: DiaGeometry) extends Checkable {
  override def validationErrors(): Seq[String] = Seq()
}


abstract class DiaClassRefBase {
  def emitCode(): String

  def emitCodeWithFullName(): String = emitCode()

  def inUserPackage(p: String): Boolean

  def mapRecursivelyUserClassReferences(f: DiaUserClassRef => DiaUserClassRef): DiaClassRefBase = this
}

object DiaClassRefBase {
  def createUncheckedUserClassRef(i: String): DiaUserClassRef = DiaEntity.parseClassNameWithPackage(i) |> { case (name, pck) => DiaUserClassRef(name, pck)}

  def isScalaClass(n: String): Boolean = DiaScalaClassRef.ScalaClasses.contains(n)

  def isGenericClass(n: String): Boolean = !isFunctionClass(n) && n.contains("[")

  def isFunctionClass(i: String): Boolean = DiaFunctionClassRef.chopFunction(i).size == 2

  def isTupleClass(i: String) = i.startsWith("(") && i.endsWith(")") && !isFunctionClass(i)

  def fromStringUnchecked(i: String): Option[DiaClassRefBase] = {
    if (i.isEmpty) None
    else if (isScalaClass(i)) DiaScalaClassRef(i).some
    else if (isGenericClass(i)) DiaGenericClassRef.fromString(i).some
    else if (isFunctionClass(i)) DiaFunctionClassRef.fromString(i).some
    else if (isTupleClass(i)) DiaTupleClassRef.fromString(i).some
    else createUncheckedUserClassRef(i).some
  }

  def fromStringUncheckedEmptyAllowed(i: String): DiaClassRefBase = fromStringUnchecked(i).getOrElse(DiaEmptyClassRef)

}

abstract class DiaNonGenericClassRefBase extends DiaClassRefBase {
  def fullName: String
}

case class DiaScalaClassRef(name: String) extends DiaNonGenericClassRefBase {
  def fullName = name

  override def emitCode(): String = fullName

  override def inUserPackage(p: String): Boolean = false
}

object DiaScalaClassRef {
  final val ScalaClasses = Seq("Seq", "Int", "String", "Double", "Float", "Map", "List", "Set", "Option", "Either", "Char", "Boolean", "Byte", "Short", "Long", "Any", "AnyVal", "AnyRef", "Unit", "Array")

  def fromString(name: String): DiaScalaClassRef = {
    if (!ScalaClasses.contains(name)) throw new RuntimeException(s"Class name '$name' is not a Scala type.")
    DiaScalaClassRef(name)
  }
}

case object DiaEmptyClassRef extends DiaClassRefBase {
  override def emitCode(): String = ""

  override def inUserPackage(p: String): Boolean = false
}

case class DiaUserClassRef(name: String, inPackage: String) extends DiaNonGenericClassRefBase {
  lazy val fullName = (if (inPackage.isEmpty) "" else inPackage + ".") + CodeEmitterHelper.sanitizeName(name)

  override def emitCode(): String = CodeEmitterHelper.sanitizeName(name)

  override def emitCodeWithFullName(): String = fullName

  override def inUserPackage(p: String): Boolean = p == inPackage

  override def mapRecursivelyUserClassReferences(f: DiaUserClassRef => DiaUserClassRef): DiaClassRefBase = f(this)
}

case class DiaGenericClassRef(base: DiaNonGenericClassRefBase, params: Seq[DiaClassRefBase]) extends DiaClassRefBase {
  // TODO: [low priority] support for "with" (easy workaround)

  override def emitCode(): String = base.emitCode() + "[" + params.map(_.emitCode()).mkString(", ") + "]"

  override def inUserPackage(p: String): Boolean = base.inUserPackage(p)

  override def mapRecursivelyUserClassReferences(f: DiaUserClassRef => DiaUserClassRef): DiaClassRefBase =
    copy(base = base.mapRecursivelyUserClassReferences(f).asInstanceOf[DiaNonGenericClassRefBase], params = params.map(_.mapRecursivelyUserClassReferences(f)))
}

object DiaGenericClassRef {
  def fromString(s: String): DiaGenericClassRef = {
    if (!DiaClassRefBase.isGenericClass(s)) throw new RuntimeException(s"$s is not a generic class.")
    val (nameWithPackage, genericRest) = s.split("\\[", 2) |> { a => (a(0), a(1).dropRight(1))}
    val params = genericRest |> chopGenericParameters |> {_.map(DiaClassRefBase.fromStringUnchecked).map(_.get)}
    DiaGenericClassRef(
      DiaClassRefBase.fromStringUnchecked(nameWithPackage).get.asInstanceOf[DiaNonGenericClassRefBase],
      params
    )
  }

  def chopGenericParameters(i: String): Seq[String] = {
    @tailrec def loop(rest: String, buff: String, counter: Int, res: Seq[String]): Seq[String] =
      if (rest.isEmpty) res ++ Seq(buff)
      else {
        val (newCounter, appendChar, flipBuff) = rest.head match {
          case '[' | '(' => (counter + 1, true, false)
          case ']' | ')' => (counter - 1, true, false)
          case ',' => if (counter == 0) (0, false, true) else (counter, true, false)
          case ' ' => (counter, false, false)
          case _ => (counter, true, false)
        }
        loop(
          rest.tail,
          if (appendChar) buff + rest.head
          else if (flipBuff) "" else buff,
          newCounter,
          if (flipBuff) res ++ Seq(buff) else res
        )
      }

    loop(i, "", 0, Seq())
  }

  def createOption(inner: DiaClassRefBase): DiaGenericClassRef = DiaGenericClassRef(DiaScalaClassRef("Option"), Seq(inner))

  def createSeq(inner: DiaClassRefBase): DiaGenericClassRef = DiaGenericClassRef(DiaScalaClassRef("Seq"), Seq(inner))
}

case class DiaFunctionClassRef(inputs: Seq[DiaClassRefBase], output: DiaClassRefBase) extends DiaClassRefBase {
  override def emitCode(): String = {
    val inPart =
      if (inputs.size == 1) inputs(0).emitCode()
      else inputs.map(_.emitCode()).mkString("(", ", ", ")")
    inPart + " => " + output.emitCode()
  }

  override def inUserPackage(p: String): Boolean = false

  override def mapRecursivelyUserClassReferences(f: DiaUserClassRef => DiaUserClassRef): DiaClassRefBase = copy(inputs = inputs.map(_.mapRecursivelyUserClassReferences(f)), output = output.mapRecursivelyUserClassReferences(f))
}

object DiaFunctionClassRef {
  def fromString(i: String): DiaFunctionClassRef = {
    val (paramsRaw, outputRaw) = chopFunction(i) |> { x => (x(0), x(1))}
    val paramsPossiblyTupledOrEmpty = DiaClassRefBase.fromStringUncheckedEmptyAllowed(paramsRaw)
    val params = paramsPossiblyTupledOrEmpty match {
      case DiaTupleClassRef(parSeq) => parSeq
      case r: Seq[DiaClassRefBase] => r
      case e: DiaClassRefBase => Seq(e)
    }
    DiaFunctionClassRef(params, DiaClassRefBase.fromStringUnchecked(outputRaw).get)
  }

  def chopFunction(i: String): Seq[String] = {
    @tailrec def loop(in: String, count: Int, buff: String, lastWasEq: Boolean, res: Seq[String]): Seq[String] =
      if (in.isEmpty) {
        if (count != 0) throw new RuntimeException(s"Type description has even count of parenthesis, critical error - '$i'.")
        res :+ buff
      } else {
        val lowestLevel = count == 0
        val (counterDiff, append, flipBuff, eq) = in.head match {
          case '(' | '[' => (1, true, false, false)
          case ')' | ']' => (-1, true, false, false)
          case '=' => (0, !lowestLevel, false, lowestLevel)
          case '>' => (0, !lowestLevel, lastWasEq, false)
          case ' ' => (0, false, false, false)
          case c: Char => (0, true, false, false)
        }
        val newBuff = buff + (if (append) in.head else "")
        loop(
          in.tail,
          count + counterDiff,
          if (!flipBuff) newBuff else "",
          eq,
          if (flipBuff) res :+ newBuff else res
        )
      }

    loop(i, 0, "", lastWasEq = false, Seq())
  }
}

case class DiaTupleClassRef(params: Seq[DiaClassRefBase]) extends DiaClassRefBase {
  override def emitCode(): String = params.map(_.emitCode()).mkString("(", ", ", ")")

  override def inUserPackage(p: String): Boolean = false

  override def mapRecursivelyUserClassReferences(f: DiaUserClassRef => DiaUserClassRef): DiaClassRefBase = copy(params = params.map(_.mapRecursivelyUserClassReferences(f)))
}

object DiaTupleClassRef {
  def chopTuple(i: String): Seq[String] = {
    @tailrec def loop(rest: String, buff: String, counter: Int, res: Seq[String]): Seq[String] =
      if (rest.isEmpty) res ++ Seq(buff)
      else {
        val (newCounter, appendChar, flipBuff) = rest.head match {
          case '(' => (counter + 1, true, false)
          case ')' => (counter - 1, true, false)
          case ',' => if (counter == 0) (0, false, true) else (counter, true, false)
          case ' ' => (counter, false, false)
          case _ => (counter, true, false)
        }
        loop(
          rest.tail,
          if (appendChar) buff + rest.head
          else if (flipBuff) "" else buff,
          newCounter,
          if (flipBuff) res ++ Seq(buff) else res
        )
      }

    if (i.head != '(' || i.last != ')') throw new RuntimeException(s"Input doesn't seem to be a tuple.")
    loop(i.drop(1).dropRight(1), "", 0, Seq())
  }

  def fromString(i: String): DiaTupleClassRef =
    if (i == "()") {
      DiaTupleClassRef(Seq())
    } else {
      val chopped = chopTuple(i)
      val res = chopped.map { x => (x, DiaClassRefBase.fromStringUnchecked(x))}.map {
        case (orig, None) => throw new RuntimeException(s"Unable to parse: '$orig' (${chopped.mkString("  ;  ")})")
        case (_, Some(r)) => r
      }
      DiaTupleClassRef(res)
    }
}

case class DiaEntity(ref: DiaUserClassRef, geometry: DiaGeometry, extendsFrom: Option[DiaClassRefBase], mixins: Seq[DiaClassRefBase], id: String, attributes: Seq[DiaAttribute], operations: Seq[DiaOperationDescriptor], classType: DiaEntityType, mutable: Boolean, immutable: Boolean, hasCompanionObject: Boolean) extends Checkable {
  override def validationErrors(): Seq[String] = {
    ({
      if (ref.name.trim.isEmpty) Seq(Seq(s"Class with empty name ($id)."))
      else if (mutable && immutable) Seq(Seq(s"Class cannot be mark as mutable AND immutable at a same time ($id)."))
      else Seq()
    }: Seq[Seq[String]]).flatten
  }
}

object DiaEntity {
  /**
   * @param i Full name in dot notation.
   * @return (name, package)
   */
  def parseClassNameWithPackage(i: String): (String, String) = i.split("\\.") |> { a => (a.last, a.init.mkString("."))}
}

case class DiaOperationDescriptor(name: String, visibility: DiaVisibility, parameters: Seq[DiaOperationParameter], oType: Option[DiaClassRefBase], isOverriding: Boolean, isClassScope: Boolean) {
  def hasSameParameterTypesAs(other: DiaOperationDescriptor): Boolean =
    parameters.size == other.parameters.size &&
      parameters.zip(other.parameters).forall { case (a, b) => a.pType == b.pType}
}

case class DiaOperationParameter(name: String, pType: Option[DiaClassRefBase])

case class DiaAttribute(name: String, aType: Option[DiaClassRefBase], visibility: DiaVisibility, isVal: Boolean, defaultValue: Option[String], isLazy: Boolean, isOverriding: Boolean, isClassScope: Boolean)

case class DiaGeometry(x: Double, y: Double, width: Double, height: Double) {
  def contains(other: DiaGeometry): Boolean = contains(other.x, other.y, other.width, other.height)

  def contains(ox: Double, oy: Double, oWidth: Double, oHeight: Double): Boolean =
    ox > x &&
      oy > y &&
      ox + oWidth < x + width &&
      oy + oHeight < y + height
}

case class DiaOneWayConnection(fromId: String, toId: String, cType: DiaOneWayConnectionType)

case class DiaAssociation(from: DiaAssociationNode, to: DiaAssociationNode)

case class DiaAssociationNode(id: String, arity: DiaAssociationArity, attr: DiaAttribute)


sealed class DiaAssociationArity

case object DiaArityNone extends DiaAssociationArity

case object DiaArityOne extends DiaAssociationArity

case object DiaArityMultiple extends DiaAssociationArity


sealed class DiaOneWayConnectionType

case object DiaGeneralizationType extends DiaOneWayConnectionType

case object DiaImplementsType extends DiaOneWayConnectionType

case object DiaMixinType extends DiaOneWayConnectionType

case object DiaCompanionOfType extends DiaOneWayConnectionType

