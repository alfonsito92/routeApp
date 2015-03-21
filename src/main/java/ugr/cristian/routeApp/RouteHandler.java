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

You should have getTransmitErrorCountd a copy of the GNU General Public License
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
import java.util.HashSet;

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

  private Long latencyMatrix[][];
  private Long minLatency;
  private Long mediumLatencyMatrix[][];

  private Map<Edge, Set<Packet>> edgePackets = new HashMap<Edge, Set<Packet>>();
  private ConcurrentMap<Map<Edge, Packet>, Long> packetTime = new ConcurrentHashMap<Map<Edge, Packet>, Long>();

  private boolean firstPacket=true;

  private Long standardCostMatrix[][];

  private ConcurrentMap<Edge, Map<String, ArrayList>> edgeStatistics = new ConcurrentHashMap<Edge, Map<String, ArrayList>>();
  private Map<String, Long> maxStatistics = new HashMap<String, Long>();

  private ConcurrentMap<Node, Map<Node, Path>> pathMap = new ConcurrentHashMap<Node, Map<Node, Path>>();

  private short idleTimeOut = 30;
  private short hardTimeOut = 60;

  /*********Statistics Constants**********/

  private final String transmitBytes = "Transmits Bytes";
  private final String receiveBytes = "Receive Bytes";
  private final String transmitDropBytes = "Transmit Drop Bytes";
  private final String receiveDropBytes = "Receive Drop Bytes";
  private final String transmitErrorBytes = "Transmit Error Bytes";
  private final String receiveErrorBytes = "Receive Error Bytes";
  private final String[] statisticsName = {transmitBytes, receiveBytes, transmitDropBytes,
  receiveDropBytes, transmitErrorBytes, receiveErrorBytes};

  /***************************************/

  /*************************************/
  private final Long defaultCost = 5L; //If we don't have any latency measure
  /*************************************/

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
      //Once a packet come the Topology has to be updated
      updateTopology();
      //First I get the incoming Connector where the packet came.
      NodeConnector ingressConnector = inPkt.getIncomingNodeConnector();
      Packet pkt = dataPacketService.decodeDataPacket(inPkt);
      log.trace("The packet came from " + ingressConnector + " NodeConnector");

      //Now we obtain the node where we received the packet
      Node node = ingressConnector.getNode();
      log.trace("The packet came from " + node + " Node");

      if(!hasHostConnected(ingressConnector)){
        Edge upEdge = getUpEdge(node, ingressConnector);
        if(upEdge != null){
          calculateLatency(upEdge, pkt);
        }
      }

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

          if(l4Datagram instanceof ICMP){


            //First Is necessary to check if the IP are in memory.
            if(!checkAddress(node, srcAddr)){
              learnIPAddress(node, srcAddr, ingressConnector);
            }


            NodeConnector egressConnector = getIPNodeConnector(node, dstAddr);

            if(egressConnector == null){
              floodPacket(inPkt, node, ingressConnector);
            }
            else{
              if(programFlow( srcAddr, srcMAC_B, dstAddr, dstMAC_B, egressConnector, node) ){

                log.trace("Flow installed on " + node + " in the port " + egressConnector);

              }
              else{
                log.trace("Error trying to install the flow");
              }

              if(!hasHostConnected(egressConnector)){
                Edge downEdge = getDownEdge(node, egressConnector);
                if(downEdge!= null){
                  putPacket(downEdge, pkt);
                }
              }

              //Send the packet for the selected Port.
              inPkt.setOutgoingNodeConnector(egressConnector);
              this.dataPacketService.transmitDataPacket(inPkt);

              traceLongMatrix(this.standardCostMatrix);

            }
            return PacketResult.CONSUME;
          }
        }

      }

      return PacketResult.IGNORED;

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
    *This function try to assing a cost for each Edge attending the latency and
    *medium latency and other aspects like statistics
    */

    private void buildStandardCostMatrix(){
      int l = this.nodeEdges.size();
      int h = this.nodeEdges.size();
      this.standardCostMatrix = new Long[l][h];

      for(int i=0; i<l; i++){

        for(int j=0; j<h; j++){

          if(i==j){
            this.standardCostMatrix[i][j]=0L;
          }
          else{
            if(this.edgeMatrix[i][j]==null){
              this.standardCostMatrix[i][j]=null;
            }
            else{
              this.standardCostMatrix[i][j] = 2L*standardLatencyCost(this.edgeMatrix[i][j]) +
              standardStatisticsMapCost(this.edgeMatrix[i][j]);
            }
          }
        }
      }
    }

    /**
    *This function is called when a Packet come and is necessary to calculate the latency
    *@param edge The associate edge
    *@param packet The packet which came just now
    */

    private void calculateLatency(Edge edge, Packet packet){

      Set<Packet> temp = this.edgePackets.get(edge);
      if(checkSetPacket(packet, temp)){
        temp.remove(packet);
        this.edgePackets.remove(edge);
        this.edgePackets.put(edge, temp);
        Long t2 = System.nanoTime();
        Long t1 = returnPacketTime(edge, packet);

        if(t1!=null){
          Long t = t2-t1;
          updateLatencyMatrix(edge, t);
          if(minLatency == null){
            minLatency=t;
          }
          else if(minLatency> t){
            minLatency=t;
          }
        }

      }

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
      return this.tableIP.containsKey(temp);

    }

    /**
    *This function check is a Packet is contained in a Set<Packet>
    *@param packet The packet
    *@param packets The set of packets
    *@return true if is contained or farse is not
    */

    private boolean checkSetPacket(Packet packet, Set<Packet> packets){

      for(Iterator<Packet> it = packets.iterator(); it.hasNext();){

        Packet temp = it.next();
        if(temp.equals(packet)){
          return true;
        }
      }

      return false;

    }

    /**Function that is called when is necessary to compare the statistics
    *@param m1 The HeadNodeConnector Statistic
    *@param m2 The tailNodeConnector Statistic
    *@param compare The string which identify the statistic
    */

    private void compareStatistic(Long m1, Long m2, String compare){

      Long temp = this.maxStatistics.get(compare);

      if(m1>m2){
        if(temp==null){
          temp=m1;
          this.maxStatistics.put(compare, temp);
        }else if(m1>temp){
            temp=m1;
            this.maxStatistics.remove(compare);
            this.maxStatistics.put(compare, temp);
          }
      }else{
        if(temp==null){
          temp=m2;
          this.maxStatistics.put(compare, temp);
        }else if(m2>temp){
            temp=m2;
            this.maxStatistics.remove(compare);
            this.maxStatistics.put(compare, temp);
          }
      }

    }

    /**
    *This function return the NodeConnector where a Host is attached
    *@param srcIP The inetAddress
    *@return NodeConnector The NodeConnector where the InetAddress is attached
    */

    private NodeConnector findHost(InetAddress srcIP){

      Set<NodeConnector> connectors = this.topologyManager.getNodeConnectorWithHost();

      for (Iterator<NodeConnector> it = connectors.iterator(); it.hasNext(); ) {
         NodeConnector temp = it.next();
         List<Host> hosts= this.topologyManager.getHostsAttachedToNodeConnector(temp);

         for(Iterator<Host> ith = hosts.iterator(); ith.hasNext();){

           Host temp2 = ith.next();
           if(temp2.getNetworkAddress().equals(srcIP)){
             return temp;
           }

         }

      }
      return null;

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
        Packet pkt = dataPacketService.decodeDataPacket(inPkt);

        for (NodeConnector p : nodeConnectors) {
          if (!p.equals(ingressConnector)) {
            try {

              RawPacket destPkt = new RawPacket(inPkt);

              if(!hasHostConnected(p)){
                Edge downEdge = getDownEdge(node, p);
                if(downEdge!=null){
                  putPacket(downEdge, pkt);
                }
              }
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
    *Function that is called when is necessary to obtain a Edge through a NodeConnector
    *We suppose that each NodeConnector has only one Edge and the nodeConnector is the head
    *@param node The actual node
    *@param node The required node
    *@param InetAddres The IP required
    *@return NodeConnector The Connector for this association.
    */

    private NodeConnector getIPNodeConnector(Node node, InetAddress addr){

      Map<Node, InetAddress> temp = new HashMap<Node, InetAddress>();
      temp.put(node, addr);
      return this.tableIP.get(temp);

    }

    /**
    *Function that is called when is necessary to obtain a Edge through a NodeConnector
    *We suppose that each NodeConnector has only one Edge and the NodeConnector is the Head
    *@param node The actual node
    *@param connector The NodeConnector which identify the Edge
    *@return The Edge corresponding the nodeConnector
    */

    private Edge getDownEdge(Node node, NodeConnector connector){
      Set<Edge> edges = this.nodeEdges.get(node);

      for(Iterator<Edge> it = edges.iterator(); it.hasNext();){
        Edge temp = it.next();

        if(temp.getHeadNodeConnector().equals(connector)){
          log.trace("Found the DOWN edge corresponding the connector " + connector + " " + temp);
          return temp;
        }

      }
      return null;
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
    *Function that is called when is necessary to obtain a Edge through a NodeConnector
    *We suppose that each NodeConnector has only one Edge and the NodeConnector is the Tail
    *@param node The actual node
    *@param connector The NodeConnector which identify the Edge
    *@return The Edge corresponding the nodeConnector
    */

    private Edge getUpEdge(Node node, NodeConnector connector){
      Set<Edge> edges = this.nodeEdges.get(node);

      for(Iterator<Edge> it = edges.iterator(); it.hasNext();){
        Edge temp = it.next();

        if(temp.getTailNodeConnector().equals(connector)){
          log.trace("Found the UP edge corresponding the connector " + connector + " " + temp);
          return temp;
        }

      }
      return null;
    }

    /**
    *This function return true if the NodeConnector has some Host attached
    *@param connector The NodeConnector
    *@return true if yes or false if not
    */

    private boolean hasHostConnected(NodeConnector connector){

      if(topologyManager.getHostsAttachedToNodeConnector(connector) != null){
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
      this.tableIP.put(temp, connector);

    }

    /**
    *Function that is called when is necesarry to install
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
    *Function that is called when is necessary to put a edge in the edgeMatrix
    *@param edge The edge that it will be put in the matrix.
    */

    private void putEdge(Edge edge){

      int node1 = getNodeConnectorIndex(edge.getHeadNodeConnector());
      int node2 = getNodeConnectorIndex(edge.getTailNodeConnector());

      this.edgeMatrix[node1][node2] = edge;
      log.trace("Put the edge in the position: " + node1 + " " +node2);

    }

    /**
    *Function that is called when is necessary to add a Edge to statistics
    *@param edge The edge
    */

    private void putEdgeStatistics(Edge edge){

      NodeConnector head = edge.getHeadNodeConnector();
      NodeConnector tail = edge.getTailNodeConnector();

      NodeConnectorStatistics headStatistics = this.statisticsManager.getNodeConnectorStatistics(head);
      NodeConnectorStatistics tailStatistics = this.statisticsManager.getNodeConnectorStatistics(tail);

      Long m1, m2;

      //////////////////////////////////
      ArrayList<Long> tempArray = new ArrayList<Long>();
      m1=headStatistics.getTransmitByteCount();
      m2=tailStatistics.getTransmitByteCount();
      tempArray.add(m1);
      tempArray.add(m2);
      compareStatistic(m1, m2, transmitBytes);

      Map<String, ArrayList> tempMap =  new HashMap<String, ArrayList>();
      tempMap.put(transmitBytes, tempArray);

      /////////////////////////////
      tempArray=new ArrayList<Long>();
      m1=headStatistics.getReceiveByteCount();
      m2=tailStatistics.getReceiveByteCount();
      tempArray.add(m1);
      tempArray.add(m2);

      compareStatistic(m1, m2, receiveBytes);

      tempMap.put(receiveBytes, tempArray);
      //////////////////////////////////
      tempArray=new ArrayList<Long>();
      m1=headStatistics.getTransmitDropCount();
      m2=tailStatistics.getTransmitDropCount();
      tempArray.add(m1);
      tempArray.add(m2);

      compareStatistic(m1, m2, transmitDropBytes);

      tempMap.put(transmitDropBytes, tempArray);
      /////////////////////////////////////
      tempArray=new ArrayList<Long>();
      m1=headStatistics.getReceiveDropCount();
      m2=tailStatistics.getReceiveDropCount();
      tempArray.add(m1);
      tempArray.add(m2);

      compareStatistic(m1, m2, receiveDropBytes);

      tempMap.put(receiveDropBytes, tempArray);
      ///////////////////////////////////////
      tempArray=new ArrayList<Long>();
      m1=headStatistics.getTransmitErrorCount();
      m2=tailStatistics.getTransmitErrorCount();
      tempArray.add(m1);
      tempArray.add(m2);

      compareStatistic(m1, m2, transmitErrorBytes);

      tempMap.put(transmitErrorBytes, tempArray);
      ///////////////////////////////////////
      tempArray=new ArrayList<Long>();
      m1=headStatistics.getReceiveErrorCount();
      m2=tailStatistics.getReceiveErrorCount();
      tempArray.add(m1);
      tempArray.add(m2);

      compareStatistic(m1, m2, receiveErrorBytes);

      tempMap.put(receiveErrorBytes, tempArray);
      ///////////////////////////////////////
      this.edgeStatistics.put(edge, tempMap);

    }

    /**
    *This function put a new association Edge Packet en the Map and put the thime in the PacketTime map
    *@parameter edge The edge
    *@parameter packet The packet
    */

    private void putPacket(Edge edge, Packet packet){

      Set<Packet> temp = this.edgePackets.get(edge);

      if(temp != null){
        if(temp.contains(packet)){
          temp.remove(packet);
          this.edgePackets.remove(edge);
          this.edgePackets.put(edge, temp);
          removePacketTime(edge, packet);
        }
        else{
          temp.add(packet);
          this.edgePackets.remove(edge);
          this.edgePackets.put(edge, temp);
          Long t = System.nanoTime();
          Map<Edge, Packet> temp2 = new HashMap<Edge, Packet>();
          temp2.put(edge, packet);
          this.packetTime.put(temp2, t);
        }
      }
      else{
        temp = new HashSet<Packet>();
        temp.add(packet);
        this.edgePackets.remove(edge);
        this.edgePackets.put(edge, temp);
        Long t = System.nanoTime();

        Map<Edge, Packet> temp2 = new HashMap<Edge, Packet>();

        temp2.put(edge, packet);
        this.packetTime.put(temp2, t);
      }

    }

    /**
    *This function is called when a association Edge, Packet, Time are wrong.
    *@param edge The edge identificator
    *@param packet The packet
    */

    private void removePacketTime(Edge edge, Packet packet){

      Map<Edge, Packet> temp = new HashMap<Edge, Packet>();
      temp.put(edge, packet);
      this.packetTime.remove(temp);

    }

    /**
    *This function return the time for a Edge Packet association
    *@param edge The edge
    *@param packet The packet
    *@return time The stored time
    */

    private Long returnPacketTime(Edge edge, Packet packet){

      Set<Map<Edge,Packet>> temp = this.packetTime.keySet();
      Map<Edge, Packet> temp2 = new HashMap<Edge, Packet>();
      temp2.clear();
      temp2.put(edge, packet);

      for(Iterator<Map<Edge, Packet>> it = temp.iterator(); it.hasNext();){

        Map<Edge, Packet> temp3 = it.next();
        if(temp3.equals(temp2)){
          Long time = this.packetTime.get(temp3);
          this.packetTime.remove(temp3);
          log.trace("Returning time for the solicitated packet" +time);
          return time;
        }
      }

      return null;

    }

    /**
    *This function build for each node the best path to each other node.
    */

    private void routingAlgorithm(){

      Set<Node> nodes = nodeEdges.keySet();

      for(Iterator<Node> it = nodes.iterator(); it.hasNext();){

        Node srcNode = it.next();

        for(Iterator<Node> it2 = nodes.iterator(); it2.hasNext();){
          Node dstNode = it2.next();

          if(!srcNode.equals(dstNode)){
            shortesPath(srcNode, dstNode);
          }

        }

      }

    }

    /**
    *This function is called when is necessary the less cost way between two nodes
    *@param src The source Node
    *@param dst The destination node
    */

    private void shortesPath(Node src, Node dst){

      int origen = Integer.parseInt(src.getID().toString());
      int destino = Integer.parseInt(dst.getID().toString());

    }

    /**
    *This function is called when is necessary evaluate the latencyMatrix for an edge
    *@param edge The edge
    *@return The int value after the process
    */

    private Long standardLatencyCost(Edge edge){
      int i = getNodeConnectorIndex(edge.getHeadNodeConnector());
      int j = getNodeConnectorIndex(edge.getTailNodeConnector());

      Long ret1 = 0L;
      Long ret2 = 0L;

      Long cost;
      if(latencyMatrix!=null){
        Long temp = this.latencyMatrix[i][j];
        Long temp2 = this.mediumLatencyMatrix[i][j];

        if(temp == null){
          ret1=defaultCost;
        }
        else{
          ret1 = temp/minLatency;
        }

        if(temp2 == null){
          ret2=defaultCost;
        }
        else{
          ret2=temp2/minLatency;
        }
      }
      else{
        ret1=defaultCost;
        ret2=defaultCost;
      }

      cost = ret1 + ret2;
      return cost;
    }

    /**
    *This function is called when is necessary evaluate the statisticsMap for an edge
    *@param edge The edge
    *@return The int value after the process
    */

    private Long standardStatisticsMapCost(Edge edge){
      Long cost = 0L;
      Long temp1 = 0L;
      Long temp2 = 0L;

      ArrayList<Long> tempArray = new ArrayList<Long>();

      Map<String, ArrayList> tempStatistics = this.edgeStatistics.get(edge);
      for(int i=0; i<statisticsName.length; i++){
        tempArray = tempStatistics.get(statisticsName[i]);

        if(tempArray == null){

          tempArray=new ArrayList<Long>();
          tempArray.add(10L);
          tempArray.add(10L);

        }

        if(tempArray.get(0)!=0 && tempArray.get(1) !=0 ){
          Long tempMedium = (tempArray.get(0) + tempArray.get(1) )/ 2;

          Long tempMax = maxStatistics.get(statisticsName[i]);

          if(tempMax == null){
            tempMax = tempMedium;
          }


          if(tempMax/tempMedium > 9){
            cost += 1L;
          }
          else{
            cost += 10L - tempMax/tempMedium;
          }
        }
        else{
          cost += 1L;
        }
      }
      return cost;
    }

    /**
    *Function that is called when we pretend to show in log all the elements of a Matrix
    *@matrix[][] The matrix
    */

    private void traceEdgeMatrix(Edge matrix[][]){

      for(int i=0; i<matrix.length; i++){
        for(int j=0; j<matrix[i].length; j++){

          log.trace(""+matrix[i][j]);

        }
      }

    }

    /**
    *Function that is called when we pretend to show in log all the elements of a Matrix
    *@matrix[][] The matrix
    */

    private void traceLongMatrix(Long matrix[][]){

      for(int i=0; i<matrix.length; i++){
        for(int j=0; j<matrix[i].length; j++){

          log.trace("Element "+i+ " "+j+" is: "+matrix[i][j]);

        }
      }

    }

    /**
    *The follow function update the latencyMatrix for the position of the edge
    *@param edge The edge
    *@param t The latency time.
    */

    private void updateLatencyMatrix(Edge edge, Long t){
      if(firstPacket){
        this.latencyMatrix = new Long[this.nodeEdges.size()][this.nodeEdges.size()];
        this.mediumLatencyMatrix = new Long[this.nodeEdges.size()][this.nodeEdges.size()];
        firstPacket=false;
      }

      int node1 = getNodeConnectorIndex(edge.getHeadNodeConnector());
      int node2 = getNodeConnectorIndex(edge.getTailNodeConnector());

      Long temp = this.mediumLatencyMatrix[node1][node2];
      if(temp == null){
        this.mediumLatencyMatrix[node1][node2]=t;
      }
      else{
        this.mediumLatencyMatrix[node1][node2] = (t + temp)/2;
      }

      this.latencyMatrix[node1][node2] = t;
      log.trace("Put the Latency: " + t + " in the position: " + node1 + " " +node2);
    }

    /**
    *Function that is called when is necessary update the statistics.
    */

    private void updateEdgeStatistics(){
      this.edgeStatistics.clear();
      Set<Node> tempNodes = this.nodeEdges.keySet();
      for(Iterator<Node> it = tempNodes.iterator(); it.hasNext();){
        Node tempNode = it.next();
        Set<Edge> tempEdges = this.nodeEdges.get(tempNode);
          for(Iterator<Edge> it2 = tempEdges.iterator(); it2.hasNext();){
            Edge tempEdge = it2.next();
            putEdgeStatistics(tempEdge);
          }
      }

    }

    /**
    *Function that is called when is necessary update the current Topology store
    */

    private void updateTopology(){

      Map<Node, Set<Edge>> edges = this.topologyManager.getNodeEdges();
      log.trace("The map is: " + edges); //Se coloca aquí para poder visualizar
      if(nodeEdges.equals(null) || !nodeEdges.equals(edges)){
        this.nodeEdges = edges;
        buildEdgeMatrix(edges);
        log.trace("The new map is " + this.nodeEdges);
        this.firstPacket = true;
      }
      updateEdgeStatistics();
      buildStandardCostMatrix();
    }

}
