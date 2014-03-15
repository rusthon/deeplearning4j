package org.deeplearning4j.iterativereduce.actor.multilayer;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import org.deeplearning4j.datasets.DataSet;
import org.deeplearning4j.iterativereduce.actor.core.Ack;
import org.deeplearning4j.iterativereduce.actor.core.AlreadyWorking;
import org.deeplearning4j.iterativereduce.actor.core.ClearWorker;
import org.deeplearning4j.iterativereduce.actor.core.ClusterListener;
import org.deeplearning4j.iterativereduce.actor.core.GiveMeMyJob;
import org.deeplearning4j.iterativereduce.actor.core.Job;
import org.deeplearning4j.iterativereduce.actor.core.NeedsModelMessage;
import org.deeplearning4j.iterativereduce.actor.core.actor.MasterActor;
import org.deeplearning4j.iterativereduce.actor.util.ActorRefUtils;
import org.deeplearning4j.nn.BaseMultiLayerNetwork;
import org.deeplearning4j.scaleout.conf.Conf;
import org.deeplearning4j.scaleout.iterativereduce.Updateable;
import org.deeplearning4j.scaleout.iterativereduce.multi.UpdateableImpl;
import org.jblas.DoubleMatrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.OneForOneStrategy;
import akka.actor.Props;
import akka.actor.SupervisorStrategy;
import akka.actor.SupervisorStrategy.Directive;
import akka.contrib.pattern.DistributedPubSubExtension;
import akka.contrib.pattern.DistributedPubSubMediator;
import akka.contrib.pattern.DistributedPubSubMediator.Put;
import akka.dispatch.Futures;
import akka.japi.Function;
import akka.pattern.Patterns;

/**
 * Iterative reduce actor for handling batch sizes
 * @author Adam Gibson
 *
 */
public class WorkerActor extends org.deeplearning4j.iterativereduce.actor.core.actor.WorkerActor<UpdateableImpl> {
	protected BaseMultiLayerNetwork network;
	protected DoubleMatrix combinedInput;

	protected UpdateableImpl workerUpdateable;
	protected ActorRef mediator = DistributedPubSubExtension.get(getContext().system()).mediator();
	protected Cancellable heartbeat;
	protected static Logger log = LoggerFactory.getLogger(WorkerActor.class);
	public final static String SYSTEM_NAME = "Workers";
	protected int numTimesReceivedNullJob = 0;


	public WorkerActor(Conf conf) {
		super(conf);
		setup(conf);
		//subscribe to broadcasts from workers (location agnostic)
		mediator.tell(new Put(getSelf()), getSelf());

		//subscribe to broadcasts from master (location agnostic)
		mediator.tell(new DistributedPubSubMediator.Subscribe(MasterActor.BROADCAST, getSelf()), getSelf());


		//subscribe to broadcasts from master (location agnostic)
		mediator.tell(new DistributedPubSubMediator.Subscribe(id, getSelf()), getSelf());

		heartbeat();

	}

	public WorkerActor(ActorRef clusterClient,Conf conf) {
		super(conf,clusterClient);
		setup(conf);
		//subscribe to broadcasts from workers (location agnostic)
		mediator.tell(new Put(getSelf()), getSelf());

		//subscribe to broadcasts from master (location agnostic)
		mediator.tell(new DistributedPubSubMediator.Subscribe(MasterActor.BROADCAST, getSelf()), getSelf());


		//subscribe to broadcasts from master (location agnostic)
		mediator.tell(new DistributedPubSubMediator.Subscribe(id, getSelf()), getSelf());

		heartbeat();

	}





	@Override
	public void preStart() throws Exception {
		super.preStart();
		availableForWork();
	}

	public static Props propsFor(ActorRef actor,Conf conf) {
		return Props.create(WorkerActor.class,actor,conf);
	}

	public static Props propsFor(Conf conf) {
		return Props.create(WorkerActor.class,conf);
	}

	protected void confirmWorking() {
		//reply
		mediator.tell(new DistributedPubSubMediator.Publish(MasterActor.MASTER,
				getCurrent()), getSelf());	
	}

	protected void finishedWork() {
		if(getCurrent() != null) {
			getCurrent().setDone(true);
			log.info("Finished work " + id);
			//reply
			mediator.tell(new DistributedPubSubMediator.Publish(MasterActor.MASTER,
					getCurrent()), getSelf());	
		}

		clearCurrentJob();
	}


	protected void heartbeat() {
		heartbeat = context().system().scheduler().schedule(Duration.apply(10, TimeUnit.SECONDS), Duration.apply(10, TimeUnit.SECONDS), new Runnable() {

			@Override
			public void run() {
				log.info("Sending heartbeat to master");
				mediator.tell(new DistributedPubSubMediator.Publish(MasterActor.MASTER,
						register()), getSelf());	

				if(getCurrent() == null) {
					availableForWork();
				}


			}

		}, context().dispatcher());

	}

	@SuppressWarnings("unchecked")
	@Override
	public void onReceive(Object message) throws Exception {
		if (message instanceof DistributedPubSubMediator.SubscribeAck || message instanceof DistributedPubSubMediator.UnsubscribeAck) {
			DistributedPubSubMediator.SubscribeAck ack = (DistributedPubSubMediator.SubscribeAck) message;
			//reply
			mediator.tell(new DistributedPubSubMediator.Publish(ClusterListener.TOPICS,
					message), getSelf());	

			log.info("Subscribed to " + ack.toString());
		}

		else if(message instanceof Job) {
			Job j = (Job) message;


			if(getCurrent() != null) {
				log.info("Job sent when already had job");
				mediator.tell(new DistributedPubSubMediator.Publish(MasterActor.MASTER,
						new AlreadyWorking(id)), getSelf());	
			}

			else {

				log.info("Confirmation from " + j.getWorkerId() + " on work");
				setCurrent(j);
				List<DataSet> input = (List<DataSet>) j.getWork();
				confirmWorking();
				updateTraining(input);

			}


		}

		else if(message instanceof BaseMultiLayerNetwork) {
			setNetwork((BaseMultiLayerNetwork) message);
			log.info("Set network");
		}

		else if(message instanceof Ack) {
			log.info("Ack from master on worker " + id);
		}

		else if(message instanceof GiveMeMyJob) {
			GiveMeMyJob g = (GiveMeMyJob) message;
			
			if(g.getJob() == null) {
				this.blockTillJobAvailable();
			}

			else
				current.set(g.getJob());
			log.info("Got job again for id " + id);
		}

		else if(message instanceof Updateable) {
			final UpdateableImpl m = (UpdateableImpl) message;
			Future<Void> f = Futures.future(new Callable<Void>() {

				@Override
				public Void call() throws Exception {

					if(m.get() == null) {
						log.info("Network is null, this worker has recently joined the cluster or the network was lost. Asking master for a copy of the current network");
						while(getNetwork() == null) {
							mediator.tell(new DistributedPubSubMediator.Publish(MasterActor.MASTER,
									new NeedsModelMessage(id)), getSelf());	
							try {
								Thread.sleep(15000);
							} catch (InterruptedException e) {
								Thread.currentThread().interrupt();
							}
						}


					}

					else {
						setWorkerUpdateable(m.clone());
						log.info("Updated worker network");
						if(m.get() == null) {
							log.warn("Unable to initialize network; network was null");
							throw new IllegalArgumentException("Network was null");
						}

						setNetwork(m.get().clone());
					}

					return null;
				}

			},context().dispatcher());

			ActorRefUtils.throwExceptionIfExists(f, context().dispatcher());


		}
		else
			unhandled(message);
	}

	protected  void updateTraining(List<DataSet> list) {
		DoubleMatrix newInput = new DoubleMatrix(list.size(),list.get(0).getFirst().columns);
		DoubleMatrix newOutput = new DoubleMatrix(list.size(),list.get(0).getSecond().columns);

		
		
		
		
		
		for(int i = 0; i < list.size(); i++) {
			newInput.putRow(i,list.get(i).getFirst());
			newOutput.putRow(i,list.get(i).getSecond());
		}

		setCombinedInput(newInput);
		setOutcomes(newOutput);

		Future<UpdateableImpl> f = Futures.future(new Callable<UpdateableImpl>() {

			@Override
			public UpdateableImpl call() throws Exception {
				while(getCurrent() == null) {
					log.info("Calling for job on worker " + id);
					//update parameters in master param server
					mediator.tell(new DistributedPubSubMediator.Publish(MasterActor.MASTER,
							new GiveMeMyJob(id, null)), getSelf());	
				}

				UpdateableImpl work = compute();
				log.info("Updating parent actor...");
				//update parameters in master param server
				mediator.tell(new DistributedPubSubMediator.Publish(MasterActor.MASTER,
						work), getSelf());	
				finishedWork();
				availableForWork();
				return work;
			}

		}, getContext().dispatcher());

		ActorRefUtils.throwExceptionIfExists(f, context().dispatcher());
	}

	protected void blockTillJobAvailable()	 {
		while(this.getCurrent() == null) {


			mediator.tell(new DistributedPubSubMediator.Publish(MasterActor.MASTER,
					new GiveMeMyJob(id,null)), getSelf());	

			log.info("Waiting on null job");
			try {
				Thread.sleep(10000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

	}


	protected void blockTillNetworkAvailable() {
		while(getNetwork() == null) {
			log.info("Network is null, this worker has recently joined the cluster. Asking master for a copy of the current network");
			mediator.tell(new DistributedPubSubMediator.Publish(MasterActor.MASTER,
					new NeedsModelMessage(id)), getSelf());	
			try {
				Thread.sleep(15000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

	}


	@Override
	public  UpdateableImpl compute(List<UpdateableImpl> records) {
		return compute();
	}

	@Override
	public  synchronized UpdateableImpl compute() {
		log.info("Training network");
		blockTillNetworkAvailable();
		//ensure job exists
		blockTillJobAvailable();

		if(this.getCurrent().isPretrain()) {
			log.info("Worker " + id + " pretraining");
			getNetwork().pretrain(this.getCombinedInput(), extraParams);
		}

		else {
			log.info("Worker " + id + " finetuning");
			getNetwork().finetune(outcomes, learningRate, fineTuneEpochs);
		}

		finishedWork();
		return new UpdateableImpl(getNetwork());
	}

	@Override
	public boolean incrementIteration() {
		return false;
	}

	@Override
	public void setup(Conf conf) {
		super.setup(conf);
	}


	@Override
	public SupervisorStrategy supervisorStrategy() {
		return new OneForOneStrategy(0, Duration.Zero(),
				new Function<Throwable, Directive>() {
			public Directive apply(Throwable cause) {
				log.error("Problem with processing",cause);
				return SupervisorStrategy.stop();
			}
		});
	}


	@Override
	public void aroundPostStop() {
		super.aroundPostStop();
		//replicate the network
		mediator.tell(new DistributedPubSubMediator.Publish(MasterActor.MASTER,
				new ClearWorker(id)), getSelf());
		heartbeat.cancel();
	}



	@Override
	public  UpdateableImpl getResults() {
		return workerUpdateable;
	}

	@Override
	public  void update(UpdateableImpl t) {
		this.workerUpdateable = t;
	}


	public  synchronized BaseMultiLayerNetwork getNetwork() {
		return network;
	}


	public  void setNetwork(BaseMultiLayerNetwork network) {
		this.network = network;
	}


	public  DoubleMatrix getCombinedInput() {
		return combinedInput;
	}


	public  void setCombinedInput(DoubleMatrix combinedInput) {
		this.combinedInput = combinedInput;
	}





	public  UpdateableImpl getWorkerUpdateable() {
		return workerUpdateable;
	}


	public  void setWorkerUpdateable(UpdateableImpl workerUpdateable) {
		this.workerUpdateable = workerUpdateable;
	}






}