/* Sample code for basic Server */

import org.omg.PortableInterceptor.SYSTEM_EXCEPTION;
import org.omg.PortableServer.REQUEST_PROCESSING_POLICY_ID;

import java.rmi.ConnectException;
import java.rmi.Naming;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Server extends UnicastRemoteObject
        implements ServerIntf  {
	private static final int MASTER = 1;
	private static final Boolean FORWARDER = true;
	private static final Boolean PROCESSOR = false;
//	private static HashMap<Integer, Boolean> AllServerList;
	private static ArrayList<Integer> appServerList;
	private static ArrayList<Integer> forServerList;
    private static ConcurrentLinkedQueue<Cloud.FrontEndOps.Request> localReqQueue;
	private static ServerIntf masterIntf;
	private static ServerIntf appIntf;
	private static Server server;
	private static int selfRPCPort;
    private static int basePort;
	private static boolean selfRole;
	private static String selfIP;
	private static ServerLib SL;
	private static int curRound = 0;
	private static LinkedList<Long> stats;
	private static long appLastScaleoutTime;
    private static long forLastScaleoutTime;
    private static final long APP_ADD_COOL_DOWN_INTERVAL = 5;
    private static final long FOR_ADD_COOL_DOWN_INTERVAL = 8;
    private static final long MAX_FORWARDER_NUM = 5;
    private static int startNum = 1;
    private static long systemStartTime;
	protected Server() throws RemoteException {
	}

	public static void main ( String args[] ) throws Exception {
		if (args.length != 3) throw new Exception("Need 3 args: <cloud_ip> <cloud_port>");
        systemStartTime = System.currentTimeMillis();
		selfIP = args[0];
		basePort = Integer.parseInt(args[1]);
        int vmId = Integer.parseInt(args[2]);
		selfRPCPort = basePort + vmId;
		System.out.println("selfPRPCPort:" + selfRPCPort);
		System.out.println("---vmID----" + vmId);


        SL = new ServerLib(args[0], basePort);
        if (vmId == 1){
            System.out.println("launching apps..");
            appServerList = new ArrayList<>();
            appServerList.add(SL.startVM() + basePort);
            SL.register_frontend();
            while (SL.getQueueLength() == 0);
            long time1 = System.currentTimeMillis();
            SL.dropHead();
            while (SL.getQueueLength() == 0);
            long time2 = System.currentTimeMillis();
            long interval = time2-time1;
            System.out.println("time2-time1:" + interval);
//            startNum = (int)(1000/(time2 - time1));
            if (interval < 300){
                startNum = 5;
            } else if (interval < 650){
                startNum = 4;
            } else if (interval < 2000){
                startNum = 2;
            } else {
                startNum = 1;
            }



            System.out.println("start:"+startNum);
            startNum = Math.max(startNum, 10);
            startNum = Math.min(startNum, 4);
        }


        localReqQueue = new ConcurrentLinkedQueue<>();
		LocateRegistry.createRegistry(selfRPCPort);
		server = new Server();
		Naming.rebind(String.format("//%s:%d/server", selfIP, selfRPCPort), server);

		if (vmId == MASTER){

			selfRole = FORWARDER;
			forServerList = new ArrayList<>();
			stats = new LinkedList<>();
			// provision machines according to current time


			// launch 3 appServer
			for (int i = 0; i < startNum-1; ++i){
				System.out.println("launching apps..");
				appServerList.add(SL.startVM() + basePort);
			}
            appLastScaleoutTime = System.currentTimeMillis();

			// launch 1 Forward
			for (int i = 0; i < 1; ++i){
				System.out.println("launching fors..");
				forServerList.add(SL.startVM() + basePort);
			}
            forLastScaleoutTime = System.currentTimeMillis();


		} else { // non-masetr // ask for role.
            while (true) {
                try {
                    masterIntf = (ServerIntf) Naming.lookup(String.format("//%s:%d/server", selfIP, basePort + 1));
                    break;
                } catch (Exception e) {
                    //e.printStackTrace();
                    continue;
                }
            }
			Content reply = masterIntf.getRole(selfRPCPort);
            if ((selfRole = reply.role) == FORWARDER){
                appServerList = reply.appServerList;
            }
        }

		if (selfRole == FORWARDER){
            if (vmId != 1) {
                SL.register_frontend();
            }
			while (SL.getStatusVM(2) == Cloud.CloudOps.VMStatus.Booting){
				Cloud.FrontEndOps.Request r = SL.getNextRequest();
				SL.drop(r);
			}
			while (true){
                int globalQueueLen = SL.getQueueLength();
                while (globalQueueLen > appServerList.size() ){
                    System.out.println("drop on forwarder:" + globalQueueLen);
                    SL.dropHead();
                    globalQueueLen--;
                }
				Cloud.FrontEndOps.Request r = SL.getNextRequest();
                forwardReq2(r);
//                System.out.println("forwarder joins..");

                if (vmId == 1){
                    int queueLen = SL.getQueueLength();
                    if (queueLen != 0){
                        System.out.println("global ql:" + queueLen);
                    }
                    if (queueLen > appServerList.size()){
                        scaleOutFor(1);
                    }
                }
			}
		} else { // processor
            Cloud.FrontEndOps.Request curReq;
            long lastTime = System.currentTimeMillis();
			while (true){
//                System.out.println("local ReqQueue.size:" + localReqQueue.size());
                while (localReqQueue.size() > 2){
                    System.out.println("localReqQueue.size:"+localReqQueue.size());
                    if (System.currentTimeMillis() - lastTime > APP_ADD_COOL_DOWN_INTERVAL) {
                        lastTime = System.currentTimeMillis();
                        int scaleUpNum = (int)((localReqQueue.size())/3);
                        System.out.println("asking for scale up app:" + scaleUpNum);
                        if (scaleUpNum !=0) {
                            masterIntf.scaleOutApp(scaleUpNum);
                        }
                    }
                    System.out.println("drop on app");
                    SL.drop(localReqQueue.poll());
                }
                if ((curReq = localReqQueue.poll()) != null){
//                    System.out.println("join battle...");
                    SL.processRequest(curReq);
                }
			}
		}
	}

    // todo: set a scaleup flog, return instantly.
	public void scaleOutApp(int num) throws Exception{
        System.out.println("Receiveing scale up app request:" + num);
        if (System.currentTimeMillis() - appLastScaleoutTime > APP_ADD_COOL_DOWN_INTERVAL) {
            System.out.println("scaleup accpeted. : interval:" + (System.currentTimeMillis() - appLastScaleoutTime));
            for (int i = 0; i < num; ++i) {
                System.out.println("Scale up App");
                appServerList.add(SL.startVM() + basePort);
                for (int rpcPort : forServerList) {
                    ServerIntf curForIntf = (ServerIntf) Naming.lookup(String.format("//%s:%d/server", selfIP, rpcPort));
                    curForIntf.welcomeNewApp(rpcPort);
                }
                appLastScaleoutTime = System.currentTimeMillis();
            }
        }else {
            System.out.println("scaleup refused. : interval:" + (System.currentTimeMillis() - appLastScaleoutTime));
        }
	}

    public static void scaleOutFor(int num) {
        System.out.println("Check whetther to scaleup forwarder");
        if (forServerList.size() < MAX_FORWARDER_NUM && System.currentTimeMillis() - forLastScaleoutTime > FOR_ADD_COOL_DOWN_INTERVAL) {
            System.out.println("Scalue up Forwarder");
            forServerList.add(SL.startVM() + basePort);
            forLastScaleoutTime = System.currentTimeMillis();
        }
    }

    public void welcomeNewApp(int rpcPort) throws RemoteException{
        appServerList.add(rpcPort);
    }


    public static void forwardReq2(Cloud.FrontEndOps.Request r) throws Exception{
        int RPCPort = appServerList.get(curRound);
        ServerIntf curAppIntf = null;
        while (true){
            try {
                curAppIntf = (ServerIntf) Naming.lookup(String.format("//%s:%d/server", selfIP, RPCPort));
                break;
            }catch (Exception e){
                continue;
            }
        }
        curAppIntf.addToLocalQue(r);
        curRound = (curRound + 1) % appServerList.size();
    }

    public void addToLocalQue(Cloud.FrontEndOps.Request r) throws Exception{
        localReqQueue.add(r);
//        System.out.println("localReqQueue.size:"+localReqQueue.size());
    }

	public static long forwardReq(Cloud.FrontEndOps.Request r) throws Exception{
		int RPCPort = appServerList.get(curRound);
		ServerIntf curAppIntf = null;
		while (true){
            try {
                curAppIntf = (ServerIntf) Naming.lookup(String.format("//%s:%d/server", selfIP, RPCPort));
                break;
			}catch (Exception e){
//				Thread.sleep(100);
				continue;
            }
		}
		long timeConsumed = curAppIntf.processReq(r);
		curRound = (curRound + 1) % appServerList.size();
		return timeConsumed;
	}


	public ArrayList<Integer> getAppServerList() throws RemoteException{
		return appServerList;
	}

	public Content getRole(int RPCport) throws RemoteException{
		// todo: what if a valid port???
		if (appServerList.contains(RPCport)){
			return new Content(PROCESSOR);
		}
		return new Content(FORWARDER, appServerList);
	}

	public long processReq(Cloud.FrontEndOps.Request r) throws RemoteException{
		long start = System.currentTimeMillis();
		SL.processRequest(r);
		long timeConsumed = System.currentTimeMillis() - start;
		return timeConsumed;
	}

	/**
	 * get number of needed vm in curTime
	 * @param curTime current time
	 * @return number of needed vms
     */
	private static byte[] getVMNumber(float curTime){
		byte[] ret = new byte[2];
        // ret[0]: forServer,  ret[1]: appServer
        ret[0] = 1;
        ret[1] = 1;

        /*
		if (curTime <= 6){
			ret[0] = 2;
			ret[1] = 3;
		}else if (curTime <= 16){
			ret[0] = 3;
			ret[1] = 4;
		} else {
			ret[0] = 4;
			ret[1] = 6;
		}
		*/
		return ret;
	}
}

