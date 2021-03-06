/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.entity

import scala.util.Failure
import scala.util.Success
import scala.util.Try
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import spray.json.DefaultJsonProtocol
import spray.json.DefaultJsonProtocol.BooleanJsonFormat
import spray.json.pimpAny
import spray.json.pimpString
import whisk.core.database.DocumentFactory
import whisk.core.entity.schema.PackageRecord
import spray.json.JsObject
import spray.json.JsValue
import spray.json.JsonFormat
import spray.json.RootJsonFormat
import spray.json.JsString
import spray.json.deserializationError
import spray.json.JsArray

/**
 * WhiskPackagePut is a restricted WhiskPackage view that eschews properties
 * that are auto-assigned or derived from URI: namespace and name.
 */
case class WhiskPackagePut(
    binding: Option[Binding] = None,
    parameters: Option[Parameters] = None,
    version: Option[SemVer] = None,
    publish: Option[Boolean] = None,
    annotations: Option[Parameters] = None)

/**
 * A WhiskPackage provides an abstraction of the meta-data for a whisk package
 * or package binding.
 *
 * The WhiskPackage object is used as a helper to adapt objects between
 * the schema used by the database and the WhiskPackage abstraction.
 *
 * @param namespace the namespace for the action
 * @param name the name of the action
 * @param binding an optional binding, None for provider, Some for binding
 * @param parameters the set of parameters to bind to the action environment
 * @param version the semantic version
 * @param publish true to share the action or false otherwise
 * @param annotation the set of annotations to attribute to the package
 * @throws IllegalArgumentException if any argument is undefined
 */
@throws[IllegalArgumentException]
case class WhiskPackage(
    namespace: Namespace,
    override val name: EntityName,
    binding: Option[Binding] = None,
    parameters: Parameters = Parameters(),
    version: SemVer = SemVer(),
    publish: Boolean = false,
    annotations: Parameters = Parameters())
    extends WhiskEntity(name) {

    require(binding != null || (binding map { _ != null } getOrElse true), "binding undefined")

    /** The full path to the package (namespace + name). */
    def path = namespace.addpath(name)

    /**
     * Merges parameters into existing set of parameters for package.
     * Existing parameters supersede those in p.
     */
    def ++(p: Parameters) = {
        WhiskPackage(namespace, name, binding, p ++ parameters, version, publish, annotations)
    }

    /**
     * Gets binding for package iff this is not already a package reference.
     */
    def bind = binding map { _ => None } getOrElse Some { Binding(namespace, name) }

    /**
     * Adds actions to package. The actions list is filtered so that only actions that
     * match the package are included (must match package namespace/name).
     */
    def withActions(actions: List[WhiskAction] = List()) = {
        withPackageActions(actions filter { a =>
            val pkgns = binding map { b => b.namespace.addpath(b.name) } getOrElse { namespace.addpath(name) }
            a.namespace == pkgns
        } map { a =>
            WhiskPackageAction(a.name, a.version, a.annotations)
        })
    }

    /**
     * Adds package actions to package as actions or feeds. An action is considered a feed
     * is it defined the property "feed" in the annotation. The value of the property is ignored
     * for this check.
     */
    def withPackageActions(actions: List[WhiskPackageAction] = List()) = {
        val actionGroups = actions map { a =>
            //  group into "actions" and "feeds"
            val feed = a.annotations(Parameters.Feed) map { _ => true } getOrElse false
            (feed, a)
        } groupBy { _._1 } mapValues { _.map(_._2) }
        WhiskPackageWithActions(this, actionGroups.getOrElse(false, List()), actionGroups.getOrElse(true, List()))
    }

    override def serialize: Try[PackageRecord] = Try {
        implicit val serdes = Binding.serdes
        val r = serialize[PackageRecord](new PackageRecord)
        r.binding = binding map { _.toGson } getOrElse new JsonObject()
        r.parameters = parameters.toGson
        r
    }

    override def summaryAsJson = {
        val JsObject(fields) = super.summaryAsJson
        JsObject(fields + (WhiskPackage.bindingFieldName -> binding.isDefined.toJson))
    }
}

/**
 * A specialized view of a whisk action contained in a package.
 * Eschews fields that are implied by package in a GET package response..
 */
case class WhiskPackageAction(name: EntityName, version: SemVer, annotations: Parameters)

/**
 * Extends WhiskPackage to include list of actions contained in package.
 * This is used in GET package response.
 */
case class WhiskPackageWithActions(wp: WhiskPackage, actions: List[WhiskPackageAction], feeds: List[WhiskPackageAction])

object WhiskPackage
    extends DocumentFactory[PackageRecord, WhiskPackage]
    with WhiskEntityQueries[WhiskPackage]
    with DefaultJsonProtocol {

    val bindingFieldName = "binding"
    override val collectionName = "packages"

    // A conspiring combination of legacy support and Scala compiler bugs makes
    // this harder that it should be. PS
    override implicit val serdes = {
        // This is to support records created in the old style where {} represents None.
        val tolerantOptionBindingFormat: JsonFormat[Option[Binding]] = {
            implicit val bs = Binding.serdes // helps the compiler
            val base = implicitly[JsonFormat[Option[Binding]]]
            new JsonFormat[Option[Binding]] {
                override def write(ob: Option[Binding]) = base.write(ob)
                override def read(js: JsValue) = {
                    if(js == JsObject()) None else base.read(js)
                }
            }
        }
        val e1 = implicitly[RootJsonFormat[Namespace]]
        val e2 = implicitly[RootJsonFormat[EntityName]]
        val e3 = tolerantOptionBindingFormat
        val e4 = implicitly[RootJsonFormat[Parameters]]
        val e5 = implicitly[RootJsonFormat[SemVer]]
        val e6 = implicitly[JsonFormat[Boolean]]
        val e7 = e4
        val cm = implicitly[ClassManifest[WhiskPackage]]
        // Scala compiler wasn't able to figure this out by itself :(
        jsonFormat7[Namespace,EntityName,Option[Binding],Parameters,SemVer,Boolean,Parameters,WhiskPackage](
            WhiskPackage.apply)(
            e1, e2, e3, e4, e5, e6, e7, cm
        )
    }

    override def apply(r: PackageRecord): Try[WhiskPackage] = Try {
        WhiskPackage(
            Namespace(r.namespace),
            EntityName(r.name),
            if (r.binding == null || r.binding.entrySet.isEmpty) None else Some(Binding(r.binding)),
            Parameters(r.parameters),
            SemVer(r.version),
            r.publish,
            Parameters(r.annotations)).
            revision[WhiskPackage](r.docinfo.rev)
    }

    override val cacheEnabled = true
    override def cacheKeys(w: WhiskPackage) = Set(w.docid.asDocInfo, w.docinfo)
}

/**
 * A package binding holds a reference to the providing package
 * namespace and package name.
 */
case class Binding(namespace: Namespace, name: EntityName) {
    def docid = DocId(WhiskEntity.qualifiedName(namespace, name))
    def toGson = {
        val gson = new JsonObject()
        gson.add("namespace", new JsonPrimitive(namespace.toString))
        gson.add("name", new JsonPrimitive(name()))
        gson
    }
    override def toString = WhiskEntity.qualifiedName(namespace, name)

    /**
     * Returns a Binding namespace if it is the default namespace
     * to the given one, otherwise this is an identity.
     */
    def resolve(ns: Namespace): Binding = {
        namespace match {
            case Namespace.DEFAULT => Binding(ns, name)
            case _                 => this
        }
    }
}

object Binding extends ArgNormalizer[Binding] with DefaultJsonProtocol {

    @throws[IllegalArgumentException]
    protected[entity] def apply(json: JsonObject): Binding = {
        val convert = Try { whisk.utils.JsonUtils.gsonToSprayJson(json) }
        require(convert.isSuccess, "binding malformed")
        serdes.read(convert.get)
    }

    override protected[core] implicit val serdes = jsonFormat2(Binding.apply)
}

object WhiskPackagePut extends DefaultJsonProtocol {
    implicit val serdes = jsonFormat5(WhiskPackagePut.apply)
}

object WhiskPackageAction extends DefaultJsonProtocol {
    implicit val serdes = jsonFormat3(WhiskPackageAction.apply)
}

object WhiskPackageWithActions {
    implicit val serdes = new RootJsonFormat[WhiskPackageWithActions] {
        def write(w: WhiskPackageWithActions) = {
            val JsObject(pkg) = WhiskPackage.serdes.write(w.wp)
            JsObject(pkg + ("actions" -> w.actions.toJson) + ("feeds" -> w.feeds.toJson))
        }

        def read(value: JsValue) = Try {
            val pkg = WhiskPackage.serdes.read(value)
            val actions = value.asJsObject.getFields("actions") match {
                case Seq(JsArray(as)) =>
                    as map { a => WhiskPackageAction.serdes.read(a) } toList
                case _ => List()
            }
            val feeds = value.asJsObject.getFields("feeds") match {
                case Seq(JsArray(as)) =>
                    as map { a => WhiskPackageAction.serdes.read(a) } toList
                case _ => List()
            }
            WhiskPackageWithActions(pkg, actions, feeds)
        } getOrElse deserializationError("whisk package with actions malformed")
    }
}
