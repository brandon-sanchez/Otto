package otto.account;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import otto.records.DecisionRecords;

@Component
@Profile("!aws")
class LocalRecordsEraser implements RecordsEraser {

    private final DecisionRecords records;

    LocalRecordsEraser(DecisionRecords records) {
        this.records = records;
    }

    @Override
    public void eraseUser(String userId) {
        records.deleteUser(userId);
    }
}
