package com.polidea.rxandroidble.internal.radio;

import android.os.Build;

import com.polidea.rxandroidble.internal.RxBleLog;
import com.polidea.rxandroidble.internal.RxBleRadio;
import com.polidea.rxandroidble.internal.RxBleRadioOperation;

import java.util.concurrent.Semaphore;

import rx.Observable;
import rx.Scheduler;
import rx.android.schedulers.AndroidSchedulers;

public class RxBleRadioImpl implements RxBleRadio {

    private OperationPriorityFifoBlockingQueue queue = new OperationPriorityFifoBlockingQueue();
    private final Scheduler scheduler;

    public RxBleRadioImpl(Scheduler scheduler) {

        if (isSamsungPhone() && isPhoneOsVersionJellyBeanMr2()) {
            this.scheduler = AndroidSchedulers.mainThread();
        } else {
            this.scheduler = scheduler;
        }

        new Thread(() -> {
            //noinspection InfiniteLoopStatement
            while (true) {
                try {
                    final RxBleRadioOperation rxBleRadioOperation = queue.take();
                    log("STARTED", rxBleRadioOperation);

                    /**
                     * Calling bluetooth calls before the previous one returns in a callback usually finishes with a failure
                     * status. Below Semaphore is passed to the RxBleRadioOperation and is meant to be released at appropriate time
                     * when the next operation should be able to start successfully.
                     */
                    final Semaphore semaphore = new Semaphore(0);

                    rxBleRadioOperation.setRadioBlockingSemaphore(semaphore);

                    /**
                     * In some implementations (i.e. Samsung Android 4.3) calling BluetoothDevice.connectGatt()
                     * from thread other than main thread ends in connecting with status 133. It's safer to make bluetooth calls
                     * on the main thread.
                     */
                    Observable.just(rxBleRadioOperation)
                            .observeOn(this.scheduler)
                            .subscribe(Runnable::run);

                    semaphore.acquire();
                    log("FINISHED", rxBleRadioOperation);
                } catch (InterruptedException e) {
                    RxBleLog.e(e, "Error while processing RxBleRadioOperation queue");
                }
            }
        }).start();
    }

    @Override
    public <T> Observable<T> queue(RxBleRadioOperation<T> rxBleRadioOperation) {
        return rxBleRadioOperation
                .asObservable()
                .doOnSubscribe(() -> {
                    log("QUEUED", rxBleRadioOperation);
                    queue.add(rxBleRadioOperation);
                })
                .doOnUnsubscribe(() -> {
                    if (queue.remove(rxBleRadioOperation)) {
                        log("REMOVED", rxBleRadioOperation);
                    }
                });
    }

    private void log(String prefix, RxBleRadioOperation rxBleRadioOperation) {
        RxBleLog.d("%8s %s(%d)", prefix, rxBleRadioOperation.getClass().getSimpleName(), System.identityHashCode(rxBleRadioOperation));
    }

    private boolean isPhoneOsVersionJellyBeanMr2() {
        return Build.VERSION.SDK_INT == Build.VERSION_CODES.JELLY_BEAN_MR2;
    }

    private boolean isSamsungPhone() {
        return Build.MANUFACTURER.toLowerCase().trim().equals("samsung");
    }
}
