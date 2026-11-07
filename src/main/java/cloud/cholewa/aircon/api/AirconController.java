package cloud.cholewa.aircon.api;

import cloud.cholewa.aircon.api.model.AirconReply;
import cloud.cholewa.aircon.api.model.AirconRule;
import cloud.cholewa.aircon.api.model.RuleBody;
import cloud.cholewa.aircon.rule.RuleService;
import cloud.cholewa.aircon.api.model.AutomationReply;
import cloud.cholewa.aircon.automation.AutomationService;
import cloud.cholewa.aircon.api.model.CommandRequest;
import cloud.cholewa.aircon.api.model.RoomCommandReply;
import cloud.cholewa.aircon.command.CommandService;
import cloud.cholewa.aircon.unit.UnitService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

//The API of the air conditioners of the house, under the base path /home/aircon; routed by the gateway by path, every
//method (HAS-379). Takes the request and hands it to the services
@RestController
@RequiredArgsConstructor
public class AirconController {

    private final UnitService unitService;
    private final CommandService commandService;
    private final AutomationService automationService;
    private final RuleService ruleService;

    //the rule of every unit that has one (HAS-386)
    @GetMapping("rules")
    Mono<List<AirconRule>> rules() {
        return ruleService.rules();
    }

    @GetMapping("{id}/rules")
    Mono<AirconRule> rule(@PathVariable final long id) {
        return ruleService.rule(id);
    }

    //the rule of a unit written whole - INVALID_RULE with the reason, NOT_FOUND_AIRCON for a unit not registered
    @PutMapping("{id}/rules")
    Mono<AirconRule> saveRule(@PathVariable final long id, @RequestBody final RuleBody body) {
        return ruleService.save(id, body);
    }

    @DeleteMapping("{id}/rules")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> deleteRule(@PathVariable final long id) {
        return ruleService.delete(id);
    }

    //the empty house and the grid, and when each last switched the units off (HAS-385)
    @GetMapping("automation")
    Mono<AutomationReply> automation() {
        return automationService.automation();
    }

    //every unit of the registry with its state as last read (HAS-381)
    @GetMapping
    Mono<List<AirconReply>> units() {
        return unitService.units();
    }

    @GetMapping("{id}")
    Mono<AirconReply> unit(@PathVariable final long id) {
        return unitService.unit(id);
    }

    //a command to one unit (HAS-382): 202 once the unit took it, confirmed by a read 2 s later (lastCommandConfirmed)
    @PostMapping("{id}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Mono<Void> command(@PathVariable final long id, @RequestBody final CommandRequest command,
                       @RequestParam(defaultValue = "dashboard") final String source) {
        return commandService.send(id, command, source);
    }

    //the same command to every unit of a room, one after the other - what each came to
    @PostMapping("rooms/{room}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Mono<List<RoomCommandReply>> roomCommand(@PathVariable final String room, @RequestBody final CommandRequest command,
                                             @RequestParam(defaultValue = "dashboard") final String source) {
        return commandService.sendToRoom(room, command, source);
    }

    //the units of a room by the name of the registry ("office", "hall up")
    @GetMapping("rooms/{room}")
    Mono<List<AirconReply>> room(@PathVariable final String room) {
        return unitService.room(room);
    }
}
