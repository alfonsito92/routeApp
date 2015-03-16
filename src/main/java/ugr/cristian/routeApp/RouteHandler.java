/**
Copyright (C) 2015  Cristian Alfonso Prieto Sánchez

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program. If not, see <http://www.gnu.org/licenses/>.
*/

package ugr.cristian.routeApp;

/**
*@author Cristian Alfonso Prieto Sánchez
*/

import java.lang.System;

import java.net.InetAddress;
import java.net.UnknownHostException;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

import org.opendaylight.controller.sal.action.Action;
import org.opendaylight.controller.sal.action.Output;
import org.opendaylight.controller.sal.action.SetDlDst;
import org.opendaylight.controller.sal.action.SetDlSrc;
import org.opendaylight.controller.sal.action.SetNwDst;
import org.opendaylight.controller.sal.action.SetNwSrc;
import org.opendaylight.controller.sal.core.ConstructionException;
import org.opendaylight.controller.sal.core.Node;
import org.opendaylight.controller.sal.core.Edge;
import org.opendaylight.controller.sal.core.Host;
import org.opendaylight.controller.sal.core.Path;
import org.opendaylight.controller.sal.core.NodeConnector;
import org.opendaylight.controller.sal.flowprogrammer.Flow;
import org.opendaylight.controller.sal.flowprogrammer.IFlowProgrammerService;
import org.opendaylight.controller.sal.match.Match;
import org.opendaylight.controller.sal.match.MatchType;
import org.opendaylight.controller.sal.packet.BitBufferHelper;
import org.opendaylight.controller.sal.packet.Ethernet;
import org.opendaylight.controller.sal.packet.IDataPacketService;
import org.opendaylight.controller.sal.packet.IListenDataPacket;
import org.opendaylight.controller.sal.packet.IPv4;
import org.opendaylight.controller.sal.packet.ICMP;
import org.opendaylight.controller.sal.packet.Packet;
import org.opendaylight.controller.sal.packet.PacketResult;
import org.opendaylight.controller.sal.packet.RawPacket;
import org.opendaylight.controller.sal.reader.NodeConnectorStatistics;
import org.opendaylight.controller.sal.utils.IPProtocols;
import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.controller.switchmanager.ISwitchManager;
import org.opendaylight.controller.topologymanager.ITopologyManager;
import org.opendaylight.controller.statisticsmanager.IStatisticsManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RouteHandler implements IListenDataPacket {

  private static final Logger log = LoggerFactory.getLogger(RouteHandler.class);

  private IDataPacketService dataPacketService;
  private ISwitchManager switchManager;
  private IFlowProgrammerService flowProgrammerService;
  private IStatisticsManager statisticsManager;
  private ITopologyManager topologyManager;

  private ConcurrentMap<Map<Node, InetAddress>, NodeConnector> tableIP = new ConcurrentHashMap<Map<Node, InetAddress>, NodeConnector>();
  private Map<Node, Set<Edge>> nodeEdges = new HashMap<Node, Set<Edge>>();

  private Edge edgeMatrix[][];

  private short idleTimeOut = 30;
  private short hardTimeOut = 60;

  static private InetAddress intToInetAddress(int i) {
        byte b[] = new byte[] { (byte) ((i>>24)&0xff), (byte) ((i>>16)&0xff), (byte) ((i>>8)&0xff), (byte) (i&0xff) };
        InetAddress addr;
        try {
            addr = InetAddress.getByAddress(b);
        } catch (UnknownHostException e) {
            return null;
        }

        return addr;
    }


    /**
     * Sets a reference to the requested DataPacketService
     */
    void setDataPacketService(IDataPacketService s) {
        log.trace("Set DataPacketService.");

        dataPacketService = s;
    }

    /**
     * Unsets DataPacketService
     */
    void unsetDataPacketService(IDataPacketService s) {
        log.trace("Removed DataPacketService.");

        if (dataPacketService == s) {
            dataPacketService = null;
        }
    }

    /**
     * Sets a reference to the requested SwitchManagerService
     */
    void setSwitchManagerService(ISwitchManager s) {
        log.trace("Set SwitchManagerService.");

        switchManager = s;
    }

    /**
     * Unsets SwitchManagerService
     */
    void unsetSwitchManagerService(ISwitchManager s) {
        log.trace("Removed SwitchManagerService.");

        if (switchManager == s) {
            switchManager = null;
        }
    }

    /**
     * Sets a reference to the requested FlowProgrammerService
     */
    void setFlowProgrammerService(IFlowProgrammerService s) {
        log.trace("Set FlowProgrammerService.");

        flowProgrammerService = s;
    }

    /**
     * Unsets FlowProgrammerService
     */
    void unsetFlowProgrammerService(IFlowProgrammerService s) {
        log.trace("Removed FlowProgrammerService.");

        if (flowProgrammerService == s) {
            flowProgrammerService = null;
        }
    }

    /**
     * Sets a reference to the requested StatisticsService
     */
    void setStatisticsManagerService(IStatisticsManager s) {
        log.trace("Set StatisticsManagerService.");

        statisticsManager = s;
    }

    /**
     * Unset StatisticsManager
     */
    void unsetStatisticsManagerService(IStatisticsManager s) {
        log.trace("Unset StatisticsManagerService.");

        if (  statisticsManager == s) {
            statisticsManager = null;
        }
    }

    /**
     * Sets a reference to the requested TopologyManager
     */
    void setTopologyManagerService(ITopologyManager s) {
        log.trace("Set TopologyManagerService.");

        topologyManager = s;
    }

    /**
     * Unset TopologyManager
     */
    void unsetTopologyManagerService(ITopologyManager s) {
        log.trace("Unset TopologyManagerService.");

        if (  topologyManager == s) {
            topologyManager = null;
        }
    }



    @Override
    public PacketResult receiveDataPacket(RawPacket inPkt) {

      //First I get the incoming Connector where the packet came.
      NodeConnector ingressConnector = inPkt.getIncomingNodeConnector();
      //log.debug("The packet came from " + ingressConnector + " NodeConnector");

      //Now we obtain the node where we received the packet
      Node node = ingressConnector.getNode();
      //log.debug("The packet came from " + node + " Node");

      Packet pkt = dataPacketService.decodeDataPacket(inPkt);

      if(pkt instanceof Ethernet) {
        //Pass the Ethernet Packet
        Ethernet ethFrame = (Ethernet) pkt;
        byte[] srcMAC_B = (ethFrame).getSourceMACAddress();
        long srcMAC = BitBufferHelper.toNumber(srcMAC_B);
        byte[] dstMAC_B = (ethFrame).getDestinationMACAddress();
        long dstMAC = BitBufferHelper.toNumber(dstMAC_B);
        Object l3Pkt = ethFrame.getPayload();

        if(l3Pkt instanceof IPv4){
          IPv4 ipv4Pkt = (IPv4)l3Pkt;
          InetAddress srcAddr = intToInetAddress(ipv4Pkt.getSourceAddress());
          InetAddress dstAddr = intToInetAddress(ipv4Pkt.getDestinationAddress());
          Object l4Datagram = ipv4Pkt.getPayload();

          //////////////////
          updateTopology();
          /////////////////

          if(!checkAddress(node, srcAddr)){
            learnIPAddress(node, srcAddr, ingressConnector);
          }


          NodeConnector egressConnector = getIPNodeConnector(node, dstAddr);

          if(egressConnector == null){
            floodPacket(inPkt, node, ingressConnector);
          }
          else{
            if(programFlow( srcAddr, srcMAC_B, dstAddr, dstMAC_B, egressConnector, node) ){

              //log.debug("Flow installed on " + node + " in the port " + egressConnector);

            }
            else{
              //log.debug("Error trying to install the flow");
            }
            inPkt.setOutgoingNodeConnector(egressConnector);
            this.dataPacketService.transmitDataPacket(inPkt);
          }

          return PacketResult.CONSUME;

        }

      }

      return PacketResult.IGNORED;

    }

    /**
    *Function that is called when is neccessary check if a IPAddress are in memory or not
    *@param node The node where we have to check
    *@param addr The IPAddress which is necessary to check
    *@return The boolean which shows if the Address are or not.
    */
    private boolean checkAddress(Node node, InetAddress addr){

      Map<Node, InetAddress> temp = new HashMap<Node, InetAddress>();
      temp.put(node, addr);
      log.debug("El temp es " + temp);
      if(this.tableIP.containsKey(temp)){
        log.debug("Hola hermosa");
        return true;
      }
      else{
        return false;
      }
    }

    /**
    *Function that is called when is necessary to put a Association Node,IP and NodeConnector
    *in ourt IPTable.
    *@param node The node where we have to create the association.
    *@param InetAddres The IP Address
    *@param NodeConnector The NodeConnector where the Packet came.
    */

    private void learnIPAddress(Node node, InetAddress addr, NodeConnector connector){

      Map<Node, InetAddress> temp = new HashMap<Node, InetAddress>();
      temp.put(node, addr);
      log.debug("Guardando: " + temp + " con el connector " + connector);
      this.tableIP.put(temp, connector);

    }

    /**
    *Function that return the NodeConnector for an Association Node,IP if is possible
    *@param node The required node
    *@param InetAddres The IP required
    *@return NodeConnector The Connector for this association.
    */

    private NodeConnector getIPNodeConnector(Node node, InetAddress addr){

      Map<Node, InetAddress> temp = new HashMap<Node, InetAddress>();
      temp.put(node, addr);
      log.debug("El temp es " + temp + " y los que tengo guardados " +this.tableIP);
      return this.tableIP.get(temp);

    }

    /**
    *Function that is called when is necessary flood a determinate packet for all the nodeConnector in a node
    *@param inPkt pakect that we have to flood
    *@param node the node which solicite the listenDataPacketService
    *@param ingressConnector the "port" where the Packet came
    */

    private void floodPacket(RawPacket inPkt, Node node, NodeConnector ingressConnector) {

        Set<NodeConnector> nodeConnectors =
        this.switchManager.getUpNodeConnectors(node);

        for (NodeConnector p : nodeConnectors) {
          if (!p.equals(ingressConnector)) {
            try {
              RawPacket destPkt = new RawPacket(inPkt);
              destPkt.setOutgoingNodeConnector(p);
              this.dataPacketService.transmitDataPacket(destPkt);
            }
            catch (ConstructionException e2) {
              continue;
            }
          }
        }
    }

    /**
    *Function that is called when is necesarry to install new flow in a node.
    *All the flows will have two timeOut, idle and Hard.
    *@param srcAddr The source IPv4 Address
    *@param srcMAC_B The srcMACAddress in byte format
    *@param dstAddr The destination IPV4 Address
    *@param dstMAC_B The dstMACAddress in byte format
    *@param outConnector The dstConnector that will install on the node
    *@param node The node where the flow will be installed
    */

    private boolean programFlow(InetAddress srcAddr, byte[] srcMAC_B, InetAddress dstAddr,
    byte[] dstMAC_B, NodeConnector outConnector, Node node) {

        Match match = new Match();
        match.setField(MatchType.DL_TYPE, (short) 0x0800);  // IPv4 ethertype
        match.setField(MatchType.NW_PROTO, IPProtocols.ICMP.byteValue());
        match.setField(MatchType.NW_SRC, srcAddr);
        match.setField(MatchType.NW_DST, dstAddr);
        match.setField(MatchType.DL_SRC, srcMAC_B);
        match.setField(MatchType.DL_DST, dstMAC_B);

        List<Action> actions = new ArrayList<Action>();
        actions.add(new Output(outConnector));

        Flow f = new Flow(match, actions);

        // Create the flow
        Flow flow = new Flow(match, actions);

        flow.setIdleTimeout(idleTimeOut);
        flow.setHardTimeout(hardTimeOut);

        // Use FlowProgrammerService to program flow.
        Status status = flowProgrammerService.addFlowAsync(node, flow);
        if (!status.isSuccess()) {
            log.error("Could not program flow: " + status.getDescription());
            return false;
        }
        else{
        return true;
      }

    }

    /**
    *Function that is called when is necessary update the current Topology store
    */
    private void updateTopology(){

      Map<Node, Set<Edge>> edges = this.topologyManager.getNodeEdges();
      //log.debug("The map is: " + edges); //Se coloca aquí para poder visualizar
      if(nodeEdges.equals(null) || !nodeEdges.equals(edges)){
        this.nodeEdges = edges;
        buildEdgeMatrix(edges);
        //log.debug("The new map is " + this.nodeEdges);
      }
    }

    /**
    *Function which build the edgeMatrix
    *@param edges the topologyMap
    */

    private void buildEdgeMatrix(Map<Node, Set<Edge>> edges){

      this.edgeMatrix = new Edge[edges.size()][edges.size()];
      Set<Node> nodes = edges.keySet();

      for(Iterator<Node> it = nodes.iterator(); it.hasNext();){

        Node nodeTemp = it.next();
        Set<Edge> nodeEdges = edges.get(nodeTemp);

        for(Iterator<Edge> it2 = nodeEdges.iterator(); it2.hasNext();){

          Edge edgeTemp = it2.next();
          putEdge(edgeTemp);

        }

      }
    }

    /**
    *This method provide the possibility to get a index for a nodeConnector
    *@param nodeConnector The nodeConnector.
    */

    private int getNodeConnectorIndex(NodeConnector nodeConnector){

      int index;
      Node node = nodeConnector.getNode();
      index = Integer.parseInt(node.getID().toString());
      return index-1;

    }

    /**
    *Function that is called when is necessary to put a edge in the edgeMatrix
    *@param edge The edge that it will be put in the matrix.
    */

    private void putEdge(Edge edge){

      int node1 = getNodeConnectorIndex(edge.getHeadNodeConnector());
      int node2 = getNodeConnectorIndex(edge.getTailNodeConnector());

      this.edgeMatrix[node1][node2] = edge;
      //log.debug("Put the edge in the position: " + node1 + " " +node2);

    }






}
