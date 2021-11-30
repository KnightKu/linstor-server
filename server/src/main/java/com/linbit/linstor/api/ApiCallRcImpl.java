package com.linbit.linstor.api;

import com.linbit.linstor.LinStorException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

public class ApiCallRcImpl implements ApiCallRc
{
    private final List<RcEntry> entries = new ArrayList<>();

    public ApiCallRcImpl()
    {
    }

    public ApiCallRcImpl(RcEntry entry)
    {
        entries.add(entry);
    }

    public ApiCallRcImpl(List<RcEntry> entriesRef)
    {
        entries.addAll(entriesRef);
    }

    public void addEntry(RcEntry entry)
    {
        if (entry != null)
        {
            entries.add(entry);
        }
    }

    public void addEntry(String message, long returnCode)
    {
        ApiCallRcEntry entry = new ApiCallRcEntry();
        entry.setMessage(message);
        entry.setReturnCode(returnCode);

        addEntry(entry);
    }

    public void addEntries(ApiCallRc apiCallRc)
    {
        entries.addAll(apiCallRc.getEntries());
    }

    @Override
    public List<RcEntry> getEntries()
    {
        return entries;
    }

    @Override
    @JsonIgnore
    public boolean isEmpty()
    {
        return entries.isEmpty();
    }

    @Override
    public boolean hasErrors()
    {
        return entries.stream().anyMatch(RcEntry::isError);
    }

    @Override
    public String toString()
    {
        return "ApiCallRcImpl{" +
            "entries=" + entries +
            '}';
    }

    public static ApiCallRcImpl singletonApiCallRc(RcEntry entry)
    {
        ApiCallRcImpl apiCallRcImpl = new ApiCallRcImpl();
        apiCallRcImpl.addEntry(entry);
        return apiCallRcImpl;
    }

    public static EntryBuilder entryBuilder(long returnCodeRef, String messageRef)
    {
        return new EntryBuilder(returnCodeRef, messageRef);
    }

    public static EntryBuilder entryBuilder(RcEntry source, Long returnCodeRef, String messageRef)
    {
        EntryBuilder entryBuilder = new EntryBuilder(
            returnCodeRef != null ? returnCodeRef : source.getReturnCode(),
            messageRef != null ? messageRef : source.getMessage()
        );
        entryBuilder.setCause(source.getCause());
        entryBuilder.setCorrection(source.getCorrection());
        entryBuilder.setDetails(source.getDetails());
        entryBuilder.putAllObjRefs(source.getObjRefs());
        entryBuilder.addAllErrorIds(source.getErrorIds());
        entryBuilder.setSkipErrorReport(source.skipErrorReport());
        return entryBuilder;
    }

    public static ApiCallRcImpl singleApiCallRc(long returnCode, String message)
    {
        return singletonApiCallRc(simpleEntry(returnCode, message));
    }

    public static ApiCallRcImpl singleApiCallRc(long returnCode, LinStorException linExc)
    {
        return singletonApiCallRc(ApiCallRcImpl.copyFromLinstorExc(returnCode, linExc));
    }

    public static ApiCallRcImpl singleApiCallRc(long returnCode, String message, String cause)
    {
        return singletonApiCallRc(simpleEntry(returnCode, message, cause));
    }

    public static ApiCallRcEntry simpleEntry(long returnCodeRef, String messageRef)
    {
        return entryBuilder(returnCodeRef, messageRef).build();
    }

    public static ApiCallRcEntry simpleEntry(long returnCodeRef, String messageRef, boolean skipErrorReport)
    {
        return entryBuilder(returnCodeRef, messageRef).setSkipErrorReport(skipErrorReport).build();
    }

    public static ApiCallRcEntry simpleEntry(long returnCode, String message, String cause)
    {
        return entryBuilder(returnCode, message).setCause(cause).build();
    }

    public static class ApiCallRcEntry implements ApiCallRc.RcEntry
    {
        private long returnCode = 0;
        private Map<String, String> objRefs = new HashMap<>();
        private String message;
        private String cause;
        private String correction;
        private String details;
        private Set<String> errorIds = new TreeSet<>();
        private boolean skipErrorReport = false;

        public ApiCallRcEntry setReturnCode(long returnCodeRef)
        {
            returnCode = returnCodeRef;
            return this;
        }

        public ApiCallRcEntry setReturnCodeBit(long bitMask)
        {
            returnCode |= bitMask;
            return this;
        }

        public ApiCallRcEntry setMessage(String messageRef)
        {
            message = messageRef;
            return this;
        }

        public ApiCallRcEntry setCause(String causeRef)
        {
            cause = causeRef;
            return this;
        }

        public ApiCallRcEntry setCorrection(String correctionRef)
        {
            correction = correctionRef;
            return this;
        }

        public ApiCallRcEntry setDetails(String detailsRef)
        {
            details = detailsRef;
            return this;
        }

        public ApiCallRcEntry putObjRef(String key, String value)
        {
            objRefs.put(key, value);
            return this;
        }

        public ApiCallRcEntry putAllObjRef(Map<String, String> map)
        {
            objRefs.putAll(map);
            return this;
        }

        public ApiCallRcEntry addErrorId(String errorId)
        {
            errorIds.add(errorId);
            return this;
        }

        public ApiCallRcEntry addAllErrorIds(Set<String> errorIdsRef)
        {
            errorIds.addAll(errorIdsRef);
            return this;
        }

        public ApiCallRcEntry setSkipErrorReport(boolean skip)
        {
            skipErrorReport = skip;
            return this;
        }

        @Override
        public long getReturnCode()
        {
            return returnCode;
        }

        @Override
        public Map<String, String> getObjRefs()
        {
            return objRefs;
        }

        @Override
        public String getMessage()
        {
            return message;
        }

        @Override
        public String getCause()
        {
            return cause;
        }

        @Override
        public String getCorrection()
        {
            return correction;
        }

        @Override
        public String getDetails()
        {
            return details;
        }

        @Override
        public Set<String> getErrorIds()
        {
            return errorIds;
        }

        @Override
        @JsonIgnore
        public boolean isError()
        {
            return (returnCode & ApiConsts.MASK_ERROR) == ApiConsts.MASK_ERROR;
        }

        @Override
        @JsonGetter
        public boolean skipErrorReport()
        {
            return !isError() || skipErrorReport;
        }

        @Override
        public String toString()
        {
            StringBuilder sb = new StringBuilder();
            ApiRcUtils.appendReadableRetCode(sb, returnCode);

            return "ApiCallRcEntry{" +
                "returnCode=" + sb.toString() +
                ", objRefs=" + objRefs +
                ", message='" + message + '\'' +
                ", cause='" + cause + '\'' +
                ", correction='" + correction + '\'' +
                ", details='" + details + '\'' +
                ", errorIds=" + errorIds +
                ", skipErrorReport=" + skipErrorReport +
                '}';
        }
    }

    public static class EntryBuilder
    {
        private final long returnCode;

        private final String message;

        private String cause;

        private String correction;

        private String details;
        private boolean skipErrorReport = false;

        private Map<String, String> objRefs = new TreeMap<>();

        private Set<String> errorIds = new TreeSet<>();

        private EntryBuilder(long returnCodeRef, String messageRef)
        {
            returnCode = returnCodeRef;
            message = messageRef;
        }

        public EntryBuilder setCause(String causeRef)
        {
            cause = causeRef;
            return this;
        }

        public EntryBuilder setCorrection(String correctionRef)
        {
            correction = correctionRef;
            return this;
        }

        public EntryBuilder setDetails(String detailsRef)
        {
            details = detailsRef;
            return this;
        }

        public EntryBuilder putObjRef(String key, String value)
        {
            objRefs.put(key, value);
            return this;
        }

        public EntryBuilder putAllObjRefs(Map<String, String> objRefsRef)
        {
            objRefs.putAll(objRefsRef);
            return this;
        }

        public EntryBuilder addErrorId(String errorId)
        {
            if (errorId != null)
            {
                errorIds.add(errorId);
            }
            return this;
        }

        public EntryBuilder addAllErrorIds(Collection<String> errorIdsRef)
        {
            errorIds.addAll(errorIdsRef);
            return this;
        }

        public EntryBuilder setSkipErrorReport(boolean skip)
        {
            skipErrorReport = skip;
            return this;
        }

        public ApiCallRcEntry build()
        {
            ApiCallRcImpl.ApiCallRcEntry entry = new ApiCallRcImpl.ApiCallRcEntry();
            entry.setReturnCode(returnCode);
            entry.setMessage(message);
            entry.setCause(cause);
            entry.setCorrection(correction);
            entry.setDetails(details);
            entry.putAllObjRef(objRefs);
            entry.addAllErrorIds(errorIds);
            entry.setSkipErrorReport(skipErrorReport);
            return entry;
        }
    }

    public static RcEntry copyFromLinstorExc(long retCode, LinStorException linstorExc)
    {
        ApiCallRcImpl.ApiCallRcEntry entry = new ApiCallRcEntry();
        entry.setReturnCode(retCode);
        if (linstorExc.getDescriptionText() != null)
        {
            entry.setMessage(linstorExc.getDescriptionText());
        }
        else
        {
            entry.setMessage(linstorExc.getMessage());
        }

        if (linstorExc.getCauseText() != null)
        {
            entry.setCause(linstorExc.getCauseText());
        }
        if (linstorExc.getCorrectionText() != null)
        {
            entry.setCorrection(linstorExc.getCorrectionText());
        }
        if (linstorExc.getDetailsText() != null)
        {
            entry.setDetails(linstorExc.getDetailsText());
        }
        return entry;
    }

    public static ApiCallRcEntry copyAndPrefixMessage(String prefix, RcEntry rcEntryRef)
    {
        ApiCallRcImpl.ApiCallRcEntry ret = new ApiCallRcEntry();
        ret.returnCode = rcEntryRef.getReturnCode();
        ret.message = prefix + rcEntryRef.getMessage();

        ret.cause = rcEntryRef.getCause();
        ret.correction = rcEntryRef.getCorrection();
        ret.details = rcEntryRef.getDetails();
        ret.skipErrorReport = rcEntryRef.skipErrorReport();
        ret.errorIds = new HashSet<>(rcEntryRef.getErrorIds());
        ret.objRefs = new HashMap<>(rcEntryRef.getObjRefs());

        return ret;
    }

    public static ApiCallRcImpl copyAndPrefix(String prefix, ApiCallRcImpl apiCallRcImpl)
    {
        ApiCallRcImpl ret = new ApiCallRcImpl();
        for (RcEntry rcEntry : apiCallRcImpl.entries)
        {
            ret.addEntry(copyAndPrefixMessage(prefix, rcEntry));
        }
        return ret;
    }
}
