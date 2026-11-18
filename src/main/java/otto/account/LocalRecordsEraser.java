package otto.account;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import otto.records.RecordObjects;
import otto.records.RecordsPurge;

@Component
@Profile("!aws")
class LocalRecordsEraser implements RecordsEraser {

    private final RecordsPurge purge;

    LocalRecordsEraser(RecordObjects objects) {
        this.purge = new RecordsPurge(objects);
    }

    @Override
    public void eraseUser(String userId) {
        purge.deleteUser(userId);
    }
}
