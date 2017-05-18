package com.linbit.drbdmanage;

import com.linbit.drbdmanage.security.AccessContext;
import com.linbit.drbdmanage.security.AccessDeniedException;
import com.linbit.drbdmanage.stateflags.Flags;
import com.linbit.drbdmanage.stateflags.StateFlags;
import java.util.UUID;

/**
 *
 * @author Robert Altnoeder &lt;robert.altnoeder@linbit.com&gt;
 */
public interface VolumeDefinition
{
    public UUID getUuid();

    public ResourceDefinition getResourceDfn();

    public VolumeNumber getVolumeNumber(AccessContext accCtx)
        throws AccessDeniedException;

    public MinorNumber getMinorNr(AccessContext accCtx)
        throws AccessDeniedException;

    public void setMinorNr(AccessContext accCtx, MinorNumber newMinorNr)
        throws AccessDeniedException;

    public long getVolumeSize(AccessContext accCtx)
        throws AccessDeniedException;

    public void setVolumeSize(AccessContext accCtx, long newVolumeSize)
        throws AccessDeniedException;

    public StateFlags<VlmDfnFlags> getFlags();

    public enum VlmDfnFlags implements Flags
    {
        REMOVE(1L);

        public static final VlmDfnFlags[] ALL_FLAGS =
        {
            REMOVE
        };

        public final long flagValue;

        private VlmDfnFlags(long value)
        {
            flagValue = value;
        }

        @Override
        public long getFlagValue()
        {
            return flagValue;
        }
    }
}
