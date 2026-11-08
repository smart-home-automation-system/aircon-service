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
import cloud.cholewa.aircon.api.model.PlugReply;
import cloud.cholewa.aircon.api.model.PowerCycleReply;
import cloud.cholewa.aircon.power.PowerBucket;
import cloud.cholewa.aircon.power.PowerService;
import cloud.cholewa.aircon.filter.FilterService;
import cloud.cholewa.aircon.filter.UnitRun;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.LocalDateTime;
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
    private final PowerService powerService;
    private final FilterService filterService;

    //the filter of a unit cleaned (HAS-391): its hours start again at 0 - NOT_FOUND_AIRCON
    @PostMapping("{id}/filter-cleaned")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> filterCleaned(@PathVariable final long id) {
        return filterService.cleaned(id);
    }

    //the changes of a unit with who made them, at most 31 days - INVALID_RANGE, NOT_FOUND_AIRCON
    @GetMapping("{id}/runs")
    Mono<List<UnitRun>> runs(@PathVariable final long id,
                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) final LocalDateTime from,
                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) final LocalDateTime to) {
        return filterService.runs(id, from, to);
    }

    //the plugs that feed the units: the draw now and the energy (HAS-390)
    @GetMapping("power")
    Mono<List<PlugReply>> power() {
        return powerService.plugs();
    }

    //the power of a plug in buckets of 5 min, 30 min or 2 h, at most 31 days - INVALID_RANGE, NOT_FOUND_PLUG
    @GetMapping("power/samples")
    Mono<List<PowerBucket>> powerSamples(@RequestParam final String plug,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) final LocalDateTime from,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) final LocalDateTime to) {
        return powerService.samples(plug, from, to);
    }

    //whether the indoor plug may be power cycled now, and the last cycle
    @GetMapping("power-cycle")
    Mono<PowerCycleReply> powerCycle() {
        return powerService.powerCycle();
    }

    //the power cycle of the indoor plug, by hand only: 202 once the plug is off, on again after cycle-off-for -
    //AIRCON_RUNNING, POWER_CYCLE_IN_PROGRESS, DEVICE_FAILED; member - who asks, stored with the cycle
    @PostMapping("power-cycle")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Mono<Void> startPowerCycle(@RequestParam(required = false) @Size(max = 50) final String member) {
        return powerService.startPowerCycle(member);
    }

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
