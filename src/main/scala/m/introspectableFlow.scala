package m

import akka.actor.{ActorSystem, Props, Actor, ActorRef}
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.Source
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

trait Strategy[-T, U] {
  def apply(o: Source[T])(handler: IntrospectableFlow[U] => Unit)(implicit actorSystem: ActorSystem, materializer: FlowMaterializer): Source[U]
}

class MergeUnorderedStrategy[T] extends Strategy[IntrospectableFlow[T], T] {
  def apply(o: Source[IntrospectableFlow[T]])(handler: IntrospectableFlow[T] => Unit)(implicit actorSystem: ActorSystem, materializer: FlowMaterializer): Source[T] = {
    import MergeUnordered.EnrichedSource
    o.map { flow =>
      handler(flow)
      flow.get
    }.mergeUnordered
  }
}
object Strategy {
  implicit def mergeUnorderedStrategy[T]: Strategy[IntrospectableFlow[T], T] = new MergeUnorderedStrategy[T]
}

object GraphRegistry {
  case class EdgeProperties(
    subStream: Option[Boolean] = None,
    label: Option[String] = None)

  type Node = IntrospectableFlow[Any]
  type Edge = (IntrospectableFlow[Any], IntrospectableFlow[Any])
  case class RegisterEdge(a: Node, b: Node, properties: EdgeProperties = EdgeProperties())

  case class GraphState(nodes: List[Node], edges: List[Edge])
  case class EdgeRegistered(a: Node, b: Node, properties: EdgeProperties)
  case class NodeRegistered(a: Node)
  case object GraphInitialized
  case object GraphChanged
  case object MarkInitialized
}
class GraphRegistry(listener: ActorRef) extends Actor {
  import GraphRegistry._
  var nodes = List.empty[Node]
  var edges = List.empty[Edge]

  private def maybeRegisterNode(a: Node): Unit = if(! (nodes contains a)) {
    nodes = a :: nodes
    listener ! NodeRegistered(a)
  }

  def receive = {
    case RegisterEdge(a, b, props) =>
      maybeRegisterNode(a)
      maybeRegisterNode(b)
      edges = (a,b) :: edges
      listener ! EdgeRegistered(a, b, props)
    case MarkInitialized =>
      listener ! GraphInitialized
  }
}


trait LinkStrategy {
  def linkChild(registry: ActorRef, self: GraphRegistry.Node, downstream: GraphRegistry.Node): Unit
}
class DefaultLinkStrategy extends LinkStrategy {
  def linkChild(registry: ActorRef, self: GraphRegistry.Node, downstream: GraphRegistry.Node): Unit =
    registry ! GraphRegistry.RegisterEdge(self, downstream)
}
class GroupEdgeLinkStrategy(groupNode: GraphRegistry.Node, keyValue: Any) extends LinkStrategy {
  // here, we ignore the "self" argument, which is a temporary IntrospectableFlow object we'd prefer to not show the user
  def linkChild(registry: ActorRef, self: GraphRegistry.Node, downstream: GraphRegistry.Node): Unit =
    registry ! GraphRegistry.RegisterEdge(groupNode, downstream, GraphRegistry.EdgeProperties(subStream = Some(true), label = Some(keyValue.toString)))
}

class IntrospectableFlow[+Out](registry: ActorRef, listener: ActorRef, val nodeName: String, source: Source[Out], linkStrategy: LinkStrategy = new DefaultLinkStrategy) {
  import IntrospectableFlow._
  val id = s"${nodeName}-${nextId}"
  val wrappedSource: Source[Out] = source.map { x =>
    listener ! FlowMessage(id, x)
    x
  }

  // creates new node, registering edge downstream to it
  protected def chain[K](newNodeName: String)(fn: Source[Out] => Source[K]): IntrospectableFlow[K] = {
    val dest = new IntrospectableFlow(registry, listener, newNodeName, fn(wrappedSource))
    linkStrategy.linkChild(registry, this, dest)
    dest
  }

  def get = wrappedSource

  // I actually don't want the streams to be linked to the groupBy node, even though the groupBy node creates them; I want that to happen in the subsequent map.
  def groupBy[K](fn: Out => K): IntrospectableFlow[(K, IntrospectableFlow[Out])] = {
    lazy val groupByNode: IntrospectableFlow[(K, IntrospectableFlow[Out])] = chain("groupBy") {
      _.groupBy(fn).map {
        case (key, flow) =>
          (key, new IntrospectableFlow(registry, listener, s"groupBy:${key}", flow, new GroupEdgeLinkStrategy(groupByNode, key)))
      }
    }
    groupByNode
  }

  def map[K](fn: Out => K): IntrospectableFlow[K] =
    chain("map")(_.map(fn))

  def bottleneck[K](delayMs: Int): IntrospectableFlow[Out] =
    chain(s"bottleneck(${delayMs}ms)")(_.map { x => Thread.sleep(delayMs); x })

  // creates new node, registering upstream edges to it
  def mergeUnordered[T](implicit mergeUnorderedStrategy: Strategy[Out, T], actorSystem: ActorSystem, materializer: FlowMaterializer): IntrospectableFlow[T] = {
    lazy val mergeJunction: IntrospectableFlow[T] = chain("mergeUnordered") { src =>
      mergeUnorderedStrategy(src) { subFlow =>
        registry ! GraphRegistry.RegisterEdge(subFlow, mergeJunction, GraphRegistry.EdgeProperties(subStream = Some(true)))
      }
    }
    mergeJunction
  }

  def foreach(fn: Out => Unit)(implicit ec: ExecutionContext, materializer: FlowMaterializer): Future[Unit] = {
    registry ! GraphRegistry.MarkInitialized // this is a lie.
    (chain("foreach") { _.map(identity) }).get.foreach(fn)
  }
}

object IntrospectableFlow {
  var id: Int = 0
  case class FlowMessage(nodeId: String, msg: Any)
  def nextId: Int = synchronized {
    id += 1
    id
  }

  def apply[K](listener: ActorRef, source: Source[K])(implicit system: ActorSystem) = {
    val registry = system.actorOf(Props(new GraphRegistry(listener)))
    new IntrospectableFlow(registry, listener, "generator", source)
  }
}

