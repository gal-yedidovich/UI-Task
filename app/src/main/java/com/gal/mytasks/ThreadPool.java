package com.gal.mytasks;

import android.os.Handler;
import android.os.Looper;
import android.support.annotation.UiThread;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * Thread Pool
 * <p>
 * Runs operations asynchronously and manages threads automatically.
 * this version is a singleton.
 * This Pool enables you to execute Async UI operations
 */
public enum ThreadPool {
	instance;

	private final Handler handler = new Handler(Looper.getMainLooper());
	private int threadNums;//numbering thread names
	private DispatchQueue managerQueue; //queue that synchronize pool methods.
	private List<DispatchQueue> queues = new ArrayList<>(); //collection of dispatch queues that work concurrently.

	/**
	 * Run new runnable operation in thread pool.
	 * The pool selects the smallest queue to run the operation.
	 * <p>
	 * When all DispatchQueues has more than 5 operations, create another DispatchQueue if max capacity not reached.
	 * <p>
	 * Thread safe.
	 *
	 * @param target new operation to run a DispatchQueue.
	 */
	public void async(final Runnable target) {
		sync(() -> { //sync to manager Queue
			if (queues.size() == 0) increaseCapacity();
			DispatchQueue dq = queues.get(0);
			for (int i = 1; i < queues.size(); i++) { //find smallest queue.
				if (dq.size() == 0) break; //if current dq is empty - then no need to continue.

				DispatchQueue temp = queues.get(i);
				if (temp.size() < dq.size()) dq = temp;
			}

			dq.add(target); //add to smallest queue - will run automatically.

			//if smallest queue is big(has more than 5 operations running) & we have less than 9 queues, add new queue.
			if (dq.size() > 5 && queues.size() < 9) increaseCapacity();
		});
	}

	/**
	 * An async operation with UI synchronization
	 * calls onPre (in UI)
	 * then task (in background)
	 * then onPost (in UI)
	 * <p>
	 * if canceled in time, this method will call onCanceled instead of onPost
	 *
	 * @param task a task to be executed
	 * @param <T>  generic type that returns to UI post task
	 */
	public <T> void async(final UITask<T> task) {
		handler.post(() -> {
			task.onPre(); //UI
			async(() -> {
				T result = task.task(); //Background
				handler.post(() -> task.onPost(result)); //UI
			});
		});
	}

	/**
	 * Disposing of all the threads. cleaning up resources
	 * <p>
	 * The pool will re-activate when calling <code>async</code> methods
	 */
	public void release() {
		dispose();
	}

	/**
	 * Run operation in manager DispatchQueue,
	 * Makes operations thread safe.
	 * <p>
	 * using <code>synchronized</code> to handle race conditions
	 *
	 * @param operation runnable implementation to run synchronously in manager DispatchQueue.
	 */
	private synchronized void sync(final Runnable operation) {
		if (managerQueue == null) init();
		managerQueue.add(operation);
	}

	/**
	 * Initialize new Poll object with three dispatchQueue.
	 * and a managerQueue to synchronize operations.
	 * <p>
	 * <b>Not thread safe</b>.
	 */
	private void init() {
		//lazy loading for first async & after disposing
		managerQueue = new DispatchQueue("manager thread");
		for (int i = 0; i < 3; i++) increaseCapacity(); //start with 3 dispatch queues.
	}

	/**
	 * Add another dispatch queue to pool.
	 * <p>
	 * <b>Not thread safe</b>.
	 */
	private void increaseCapacity() {
		System.out.println("increasing capacity"); //print
		queues.add(new DispatchQueue("thread #" + ++threadNums)); //add new DP to pool
	}

	/**
	 * Event handler to handle inactive threads,
	 * when a DispatchQueue is inactive (its queue is empty), it will notify the pool
	 * <p>
	 * This method will ignore managerQueue when it is inactive.
	 * <p>
	 * Thread safe
	 *
	 * @param dq the DispatchQueue that called inactive
	 */
	private void onThreadInactive(final DispatchQueue dq) {
		if (dq == managerQueue) return; //ignore manager Queue

		sync(() -> {
			if (dq.queue.isEmpty() && queues.size() > 3) { //double check that DP is still inactive, then remove this one.
				dq.kill(); //dispose thread
				queues.remove(dq); //remove from pool
			}
		});
	}

	/**
	 * Kill all threads
	 * Thread safe
	 */
	private void dispose() {
		sync(() -> {
			System.out.println("Thread pool - kill");
			for (DispatchQueue t : queues) t.kill();
			managerQueue.kill();
			managerQueue = null;
		});
	}

	/**
	 * Dispatch Queue claa, defines a queue of runnable operation in a thread.
	 */
	private class DispatchQueue implements Runnable {
		private boolean isAlive; //boolean indicating thread is still working
		private Queue<Runnable> queue; //queue of runnable operations
		private Thread thread; //the thread which runs operations

		/**
		 * Initialize new DQ object.
		 *
		 * @param name name of the thread inside the DQ.
		 */
		DispatchQueue(String name) {
			thread = new Thread(this);
			thread.setName(name);
			queue = new ArrayDeque<>();
			isAlive = false; //at first thread is not running, then it's not alive.
		}

		/**
		 * @return size of operations queue.
		 */
		int size() {
			return queue.size();
		}

		/**
		 * Add new operation to Dispatch Queue.
		 * This Queue will start after first add call
		 *
		 * @param target new operation in queue.
		 */
		void add(Runnable target) {
			Thread.State state = thread.getState();
			if (state == Thread.State.NEW) { //lazy starting thread.
				thread.start();
				isAlive = true; //thread is now alive.
			}
			queue.add(target); //add new operation to queue.
		}

		/**
		 * Handle queue operations.
		 * When queue is not empty, run each operation by order FIFO.
		 * <p>
		 * When queue is empty, notify pool that DQ is inactive
		 * and wait until queue is not empty or thread is killed.
		 */
		@Override
		public void run() {
			while (isAlive)
				if (!queue.isEmpty()) queue.remove().run(); //run operation.
				else {
					try {
						onThreadInactive(this); //notify pool that this thread is inactive.
						Thread.sleep(20); //wait 20 ms, to save resources.
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}

			System.out.println(thread.getName() + " - Done");
		}

		/**
		 * Kill this thread.
		 * <p>
		 * Stopping it from receiving new operations, and kills the thread
		 */
		void kill() {
			isAlive = false;
		}
	}

	/**
	 * UI Task
	 *
	 * @param <T> Result from background operation
	 */
	public static abstract class UITask<T> {
		private boolean canceled = false;

		/**
		 * Task to be execute in background
		 *
		 * @return a result object, which used in onPost method
		 */
		public abstract T task();

		/**
		 * Pre execute method in UI
		 */
		@UiThread
		public void onPre() {
		}

		/**
		 * Post execute method in UI
		 */
		@UiThread
		public void onPost(T result) {
		}

		/**
		 * cancel task, will call onCancel instead of post if called in time
		 */
		public final void cancel() {
			canceled = true;
		}

		/**
		 * getter for canceled value
		 *
		 * @return ture if task is canceled, otherwise false.
		 */
		public final boolean isCanceled() {
			return canceled;
		}

		/**
		 * executing this Task
		 * <p>
		 * calling onPre() then task() then onPost(result)
		 */
		public final void execute() {
			instance.async(this);
		}
	}

	/**
	 * A UI Task with onProgress event
	 *
	 * @param <TProg>
	 * @param <TResult>
	 */
	public static abstract class ProgressedTask<TProg, TResult> extends UITask<TResult> {
		/**
		 * mid task operation that being called while 'task()' method
		 */
		public abstract void onProgress(TProg progress);

		/**
		 * calling onProgress() method in UI thread
		 */
		public final void updateProgress(TProg progress) {
			instance.handler.post(() -> onProgress(progress));
		}
	}
}
