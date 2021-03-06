/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.jicofo.recording;

import net.java.sip.communicator.impl.protocol.jabber.extensions.jirecon.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.ColibriConferenceIQ.Recording.*;
import net.java.sip.communicator.service.protocol.*;

import org.jitsi.jicofo.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.util.*;
import org.jitsi.xmpp.util.*;

import org.jivesoftware.smack.packet.*;

import java.util.*;

/**
 * Class implements {@link Recorder} using Jirecon recorder container.
 *
 * @author Pawel Domas
 */
public class JireconRecorder
    extends Recorder
{
    /**
     * The class logger which can be used to override logging level inherited
     * from {@link JitsiMeetConference}.
     */
    private final static Logger classLogger
        = Logger.getLogger(JireconRecorder.class);

    /**
     * The name of the property which specifies the token used to authenticate
     * requests to enable media recording.
     */
    static final String MEDIA_RECORDING_TOKEN_PNAME
        = "org.jitsi.videobridge.MEDIA_RECORDING_TOKEN";

    /**
     * The logger for this instance. Uses the logging level either of the
     * {@link #classLogger} or {@link JitsiMeetConference#getLogger()}
     * whichever is higher.
     */
    private final Logger logger;

    /**
     * FIXME: not sure about that
     * Our room JID in form of room_name@muc_component/focus_nickname
     */
    private final String mucRoomJid;

    /**
     * Recording authentication token.
     */
    private final String token;

    /**
     * Recording status of the Jirecon component.
     */
    private JireconIq.Status status = JireconIq.Status.UNDEFINED;

    /**
     * Recording session identifier assigned by Jirecon.
     */
    private String recordingId;

    /**
     * Creates new instance of <tt>JireconRecorder</tt>.
     * @param conference the parent conference for which this instance will be
     * handling the recording.
     * @param recorderComponentJid recorder component address.
     * @param connection XMPP connection instance which will be used for
     * communication.
     */
    public JireconRecorder(JitsiMeetConference conference,
                           String recorderComponentJid,
                           XmppConnection connection)
    {
        super(recorderComponentJid, connection);

        Objects.requireNonNull(conference, "conference");

        this.mucRoomJid = conference.getFocusJid();
        this.token
            = FocusBundleActivator.getConfigService()
                    .getString(MEDIA_RECORDING_TOKEN_PNAME);
        this.logger = Logger.getLogger(classLogger, conference.getLogger());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isRecording()
    {
        return JireconIq.Status.INITIATING == status
            || JireconIq.Status.STARTED == status;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean setRecording(
            String from, String token, State doRecord, String path)
    {
        if (!StringUtils.isNullOrEmpty(this.token)
            && !this.token.equals(token))
        {
            return false;
        }

        if (!isRecording() && doRecord.equals(State.ON))
        {
            // Send start recording IQ
            JireconIq recording = new JireconIq();

            recording.setTo(recorderComponentJid);
            recording.setType(IQ.Type.SET);
            recording.setFrom(from);

            recording.setMucJid(mucRoomJid);
            recording.setAction(JireconIq.Action.START);
            recording.setOutput(path);

            Packet reply;
            try
            {
                reply = connection.sendPacketAndGetReply(recording);
            }
            catch (OperationFailedException e)
            {
                logger.error("XMPP disconnected", e);
                return false;
            }

            if (reply instanceof JireconIq)
            {
                JireconIq recResponse = (JireconIq) reply;
                if (JireconIq.Status.INITIATING.equals(recResponse.getStatus()))
                {
                    recordingId = recResponse.getRid();
                    logger.info("Received recording ID: " + recordingId);
                    status = JireconIq.Status.INITIATING;
                }
                else
                {
                    logger.error(
                        "Unexpected status received: " + recResponse.toXML());
                }
            }
            else
            {
                logger.error(
                        "Unexpected response: " + IQUtils.responseToXML(reply));
            }
        }
        else if (isRecording() && doRecord.equals(State.OFF))
        {
            // Send stop recording IQ
            JireconIq recording = new JireconIq();

            recording.setTo(recorderComponentJid);
            recording.setType(IQ.Type.SET);
            recording.setFrom(from);

            recording.setRid(recordingId);
            recording.setMucJid(mucRoomJid);
            recording.setAction(JireconIq.Action.STOP);

            connection.sendPacket(recording);

            status = JireconIq.Status.STOPPING;
        }

        return true;
    }

    /**
     * Accepts Jirecon packets.
     *
     * {@inheritDoc}
     */
    @Override
    public boolean accept(Packet packet)
    {
        return packet instanceof JireconIq;
    }

    /**
     * Jirecon packets processing logic.
     *
     * {@inheritDoc}
     */
    @Override
    public void processPacket(Packet packet)
    {
        JireconIq recording = (JireconIq) packet;

        if (JireconIq.Action.INFO != recording.getAction()
            && IQ.Type.RESULT == recording.getType()
            || StringUtils.isNullOrEmpty(recording.getRid()))
        {
            logger.warn("Discarded: " + recording.toXML());
            return;
        }

        if (!recording.getRid().equals(recordingId))
        {
            logger.warn(
                "Received IQ for unknown session: " + recording.toXML());
            return;
        }

        if (status != recording.getStatus())
        {
            status = recording.getStatus();

            logger.info("Recording " + recordingId + " status: " + status);

            if (status == JireconIq.Status.STOPPED)
            {
                logger.info("Recording STOPPED: " + recordingId);
                recordingId = null;
            }
        }
        else
        {
            logger.info("Ignored status change: " + recording.toXML());
        }
    }
}
