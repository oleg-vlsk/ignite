// @java.file.header

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal;

import org.gridgain.grid.*;
import org.gridgain.grid.events.*;
import org.gridgain.grid.lang.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.jetbrains.annotations.*;

import java.util.*;

/**
 * {@link GridEvents} implementation.
 *
 * @author @java.author
 * @version @java.version
 */
public class GridEventsImpl implements GridEvents {
    /** */
    private final GridKernalContext ctx;

    /** */
    private final GridProjection prj;

    /**
     * @param ctx Kernal context.
     * @param prj Projection.
     */
    public GridEventsImpl(GridKernalContext ctx, GridProjection prj) {
        this.ctx = ctx;
        this.prj = prj;
    }

    /** {@inheritDoc} */
    @Override public GridProjection projection() {
        return prj;
    }

    /** {@inheritDoc} */
    @Override public <T extends GridEvent> GridFuture<List<T>> remoteQuery(GridPredicate<T> pe, long timeout) {
        A.notNull(pe, "pe");

        guard();

        try {
            return ctx.event().remoteEventsAsync(pe, prj.nodes(), timeout);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public <T extends GridEvent> GridFuture<UUID> remoteListen(@Nullable GridBiPredicate<UUID, T> locLsnr,
        @Nullable GridPredicate<T> rmtFilter, @Nullable int... types) {
        return remoteListen(1, 0, true, locLsnr, rmtFilter, types);
    }

    /** {@inheritDoc} */
    @Override public <T extends GridEvent> GridFuture<UUID> remoteListen(int bufSize, long interval,
        boolean autoUnsubscribe, @Nullable GridBiPredicate<UUID, T> locLsnr, @Nullable GridPredicate<T> rmtFilter,
        @Nullable int... types) {
        A.ensure(bufSize > 0, "bufSize > 0");
        A.ensure(interval >= 0, "interval >= 0");

        guard();

        try {
            return ctx.continuous().startRoutine(new GridEventConsumeHandler((GridBiPredicate<UUID, GridEvent>) locLsnr,
                (GridPredicate<GridEvent>) rmtFilter, types), bufSize, interval, autoUnsubscribe, prj.predicate());
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public GridFuture<?> stopRemoteListen(UUID opId) {
        A.notNull(opId, "consumeId");

        guard();

        try {
            return ctx.continuous().stopRoutine(opId);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public <T extends GridEvent> GridFuture<T> waitForLocal(@Nullable GridPredicate<T> p,
        @Nullable int... types) {
        guard();

        try {
            return ctx.event().waitForEvent(p, types);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public <T extends GridEvent> Collection<T> localQuery(GridPredicate<T> p) {
        A.notNull(p, "p");

        guard();

        try {
            return ctx.event().localEvents(p);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public void recordLocal(GridEvent evt) {
        A.notNull(evt, "evt");

        if (evt.type() <= 1000)
            throw new IllegalArgumentException("All types in range from 1 to 1000 are reserved for " +
                "internal GridGain events [evtType=" + evt.type() + ", evt=" + evt + ']');

        guard();

        try {
            ctx.event().record(evt);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public void localListen(GridPredicate<? extends GridEvent> lsnr, int[] types) {
        A.notNull(lsnr, "lsnr");
        A.notEmpty(types, "types");

        guard();

        try {
            ctx.event().addLocalEventListener(lsnr, types);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public boolean stopLocalListen(GridPredicate<? extends GridEvent> lsnr, @Nullable int... types) {
        A.notNull(lsnr, "lsnr");

        guard();

        try {
            return ctx.event().removeLocalEventListener(lsnr, types);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public void enableLocal(int[] types) {
        A.notEmpty(types, "types");

        guard();

        try {
            ctx.event().enableEvents(types);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public void disableLocal(int[] types) {
        A.notEmpty(types, "types");

        guard();

        try {
            ctx.event().disableEvents(types);
        }
        finally {
            unguard();
        }
    }

    /**
     * <tt>ctx.gateway().readLock()</tt>
     */
    private void guard() {
        ctx.gateway().readLock();
    }

    /**
     * <tt>ctx.gateway().readUnlock()</tt>
     */
    private void unguard() {
        ctx.gateway().readUnlock();
    }
}
