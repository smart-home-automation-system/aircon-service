package cloud.cholewa.aircon.automation;

import cloud.cholewa.aircon.api.model.CommandRequest;
import cloud.cholewa.aircon.api.model.RoomCommandReply;
import cloud.cholewa.aircon.command.CommandService;
import cloud.cholewa.aircon.error.AirconException;
import cloud.cholewa.aircon.state.ChangeSource;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiCommandsTest {

    private static final CommandRequest OFF = new CommandRequest(false, null, null, null, null, null, null, null, null,
        null);
    private static final List<RoomCommandReply> REPLIES = List.of(new RoomCommandReply(140, true, null));

    private final CommandService commandService = mock(CommandService.class);
    private final RuleCommands ruleCommands = mock(RuleCommands.class);
    private final ApiCommands commands = new ApiCommands(commandService, ruleCommands);

    //safety-service at a fire alarm (HAS-467): an automatic command, logged in aircon_rule_run
    @Test
    void should_send_a_command_of_safety_as_an_automatic_one() {
        when(ruleCommands.send(140, OFF, ChangeSource.SAFETY, null)).thenReturn(Mono.empty());

        commands.send(140, OFF, "safety").as(StepVerifier::create).verifyComplete();

        verify(commandService, never()).send(anyLong(), any(), anyString());
    }

    @Test
    void should_send_a_command_of_the_dashboard_straight() {
        when(commandService.send(140, OFF, "dashboard")).thenReturn(Mono.empty());

        commands.send(140, OFF, "dashboard").as(StepVerifier::create).verifyComplete();

        verify(ruleCommands, never()).send(anyLong(), any(), any(), any());
    }

    //a source CommandService does not know is its to refuse
    @Test
    void should_leave_an_unknown_source_to_the_commands() {
        when(commandService.send(140, OFF, "remote"))
            .thenReturn(Mono.error(AirconException.invalidCommand("The source of a command is ...")));

        commands.send(140, OFF, "remote").as(StepVerifier::create).expectError(AirconException.class).verify();

        verify(ruleCommands, never()).send(anyLong(), any(), any(), any());
    }

    @Test
    void should_send_a_command_of_a_room_by_safety_as_an_automatic_one() {
        when(ruleCommands.sendToRoom("office", OFF, ChangeSource.SAFETY)).thenReturn(Mono.just(REPLIES));

        commands.sendToRoom("office", OFF, "safety").as(StepVerifier::create).expectNext(REPLIES).verifyComplete();

        verify(commandService, never()).sendToRoom(anyString(), any(), anyString());
    }

    @Test
    void should_send_a_command_of_a_room_by_the_dashboard_straight() {
        when(commandService.sendToRoom("office", OFF, "dashboard")).thenReturn(Mono.just(REPLIES));

        commands.sendToRoom("office", OFF, "dashboard").as(StepVerifier::create).expectNext(REPLIES).verifyComplete();

        verify(ruleCommands, never()).sendToRoom(anyString(), any(), any());
    }
}
