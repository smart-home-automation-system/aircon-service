package cloud.cholewa.aircon.automation;

import cloud.cholewa.aircon.api.model.CommandRequest;
import cloud.cholewa.aircon.api.model.RoomCommandReply;
import cloud.cholewa.aircon.command.CommandService;
import cloud.cholewa.aircon.state.ChangeSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

//The commands of the API (HAS-467): a command of another service at a fire alarm (source=safety) is an automatic
//command - sent through RuleCommands and logged in aircon_rule_run; every other source goes to CommandService as it
//comes, which judges the source
@Service
@RequiredArgsConstructor
public class ApiCommands {

    //the senders of the API logged as automatic commands
    static final Set<ChangeSource> LOGGED = Set.of(ChangeSource.SAFETY);

    private final CommandService commandService;
    private final RuleCommands ruleCommands;

    public Mono<Void> send(final long id, final CommandRequest command, final String source) {
        return loggedSender(source)
            .map(sender -> ruleCommands.send(id, command, sender, null))
            .defaultIfEmpty(Mono.defer(() -> commandService.send(id, command, source)))
            .flatMap(Function.identity());
    }

    public Mono<List<RoomCommandReply>> sendToRoom(final String room, final CommandRequest command,
                                                   final String source) {
        return loggedSender(source)
            .map(sender -> ruleCommands.sendToRoom(room, command, sender))
            .defaultIfEmpty(Mono.defer(() -> commandService.sendToRoom(room, command, source)))
            .flatMap(Function.identity());
    }

    private static Mono<ChangeSource> loggedSender(final String source) {
        return Mono.justOrEmpty(ChangeSource.ofValue(source)).filter(LOGGED::contains);
    }
}
